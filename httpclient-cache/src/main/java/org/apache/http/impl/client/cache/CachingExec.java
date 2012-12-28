/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.http.impl.client.cache;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HeaderElement;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpMessage;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.annotation.ThreadSafe;
import org.apache.http.client.cache.CacheResponseStatus;
import org.apache.http.client.cache.HeaderConstants;
import org.apache.http.client.cache.HttpCacheContext;
import org.apache.http.client.cache.HttpCacheEntry;
import org.apache.http.client.cache.HttpCacheStorage;
import org.apache.http.client.cache.ResourceFactory;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpExecutionAware;
import org.apache.http.client.methods.HttpRequestWrapper;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.impl.client.execchain.ClientExecChain;
import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;
import org.apache.http.util.EntityUtils;
import org.apache.http.util.VersionInfo;

/**
 * <p>CachingExec is intended to transparently add client-side caching
 * to the HttpClient {@link ClientExecChain execution chain}.
 * The current implementation is conditionally compliant with HTTP/1.1
 * (meaning all the MUST and MUST NOTs are obeyed), although quite a lot,
 * though not all, of the SHOULDs and SHOULD NOTs are obeyed too.</p>
 *
 * <p>Folks that would like to experiment with alternative storage backends
 * should look at the {@link HttpCacheStorage} interface and the related
 * package documentation there. You may also be interested in the provided
 * {@link org.apache.http.impl.client.cache.ehcache.EhcacheHttpCacheStorage
 * EhCache} and {@link
 * org.apache.http.impl.client.cache.memcached.MemcachedHttpCacheStorage
 * memcached} storage backends.</p>
 * </p>
 *
 * @since 4.3
 */
@ThreadSafe // So long as the responseCache implementation is threadsafe
public class CachingExec implements ClientExecChain {

    private final static boolean SUPPORTS_RANGE_AND_CONTENT_RANGE_HEADERS = false;

    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong cacheUpdates = new AtomicLong();

    private final Map<ProtocolVersion, String> viaHeaders = new HashMap<ProtocolVersion, String>(4);

    private final CacheConfig cacheConfig;
    private final ClientExecChain backend;
    private final HttpCache responseCache;
    private final CacheValidityPolicy validityPolicy;
    private final CachedHttpResponseGenerator responseGenerator;
    private final CacheableRequestPolicy cacheableRequestPolicy;
    private final CachedResponseSuitabilityChecker suitabilityChecker;
    private final ConditionalRequestBuilder conditionalRequestBuilder;
    private final ResponseProtocolCompliance responseCompliance;
    private final RequestProtocolCompliance requestCompliance;
    private final ResponseCachingPolicy responseCachingPolicy;

    private final AsynchronousValidator asynchRevalidator;

    private final Log log = LogFactory.getLog(getClass());

    public CachingExec(
            ClientExecChain backend,
            HttpCache cache,
            CacheConfig config) {
        super();
        Args.notNull(backend, "HTTP backend");
        Args.notNull(cache, "HttpCache");
        this.cacheConfig = config != null ? config : CacheConfig.DEFAULT;
        this.backend = backend;
        this.responseCache = cache;
        this.validityPolicy = new CacheValidityPolicy();
        this.responseGenerator = new CachedHttpResponseGenerator(this.validityPolicy);
        this.cacheableRequestPolicy = new CacheableRequestPolicy();
        this.suitabilityChecker = new CachedResponseSuitabilityChecker(this.validityPolicy, config);
        this.conditionalRequestBuilder = new ConditionalRequestBuilder();
        this.responseCompliance = new ResponseProtocolCompliance();
        this.requestCompliance = new RequestProtocolCompliance();
        this.responseCachingPolicy = new ResponseCachingPolicy(
                this.cacheConfig.getMaxObjectSize(), this.cacheConfig.isSharedCache(),
                this.cacheConfig.isNeverCacheHTTP10ResponsesWithQuery());
        this.asynchRevalidator = makeAsynchronousValidator(config);
    }

    public CachingExec(
            ClientExecChain backend,
            ResourceFactory resourceFactory,
            HttpCacheStorage storage,
            CacheConfig config) {
        this(backend, new BasicHttpCache(resourceFactory, storage, config), config);
    }

    public CachingExec(ClientExecChain backend) {
        this(backend, new BasicHttpCache(), CacheConfig.DEFAULT);
    }

    CachingExec(
            ClientExecChain backend,
            HttpCache responseCache,
            CacheValidityPolicy validityPolicy,
            ResponseCachingPolicy responseCachingPolicy,
            CachedHttpResponseGenerator responseGenerator,
            CacheableRequestPolicy cacheableRequestPolicy,
            CachedResponseSuitabilityChecker suitabilityChecker,
            ConditionalRequestBuilder conditionalRequestBuilder,
            ResponseProtocolCompliance responseCompliance,
            RequestProtocolCompliance requestCompliance,
            CacheConfig config) {
        this.cacheConfig = config != null ? config : CacheConfig.DEFAULT;
        this.backend = backend;
        this.responseCache = responseCache;
        this.validityPolicy = validityPolicy;
        this.responseCachingPolicy = responseCachingPolicy;
        this.responseGenerator = responseGenerator;
        this.cacheableRequestPolicy = cacheableRequestPolicy;
        this.suitabilityChecker = suitabilityChecker;
        this.conditionalRequestBuilder = conditionalRequestBuilder;
        this.responseCompliance = responseCompliance;
        this.requestCompliance = requestCompliance;
        this.asynchRevalidator = makeAsynchronousValidator(config);
    }

    private AsynchronousValidator makeAsynchronousValidator(
            CacheConfig config) {
        if (config.getAsynchronousWorkersMax() > 0) {
            return new AsynchronousValidator(this, config);
        }
        return null;
    }

    /**
     * Reports the number of times that the cache successfully responded
     * to an {@link HttpRequest} without contacting the origin server.
     * @return the number of cache hits
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Reports the number of times that the cache contacted the origin
     * server because it had no appropriate response cached.
     * @return the number of cache misses
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Reports the number of times that the cache was able to satisfy
     * a response by revalidating an existing but stale cache entry.
     * @return the number of cache revalidations
     */
    public long getCacheUpdates() {
        return cacheUpdates.get();
    }

    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request) throws IOException, HttpException {
        return execute(route, request, HttpClientContext.create(), null);
    }

    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context) throws IOException, HttpException {
        return execute(route, request, context, null);
    }

    public CloseableHttpResponse execute(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware) throws IOException, HttpException {

        HttpHost target = route.getTargetHost();
        String via = generateViaHeader(request.getOriginal());

        // default response context
        setResponseStatus(context, CacheResponseStatus.CACHE_MISS);

        if (clientRequestsOurOptions(request)) {
            setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            return Proxies.enhanceResponse(new OptionsHttp11Response());
        }

        HttpResponse fatalErrorResponse = getFatallyNoncompliantResponse(request, context);
        if (fatalErrorResponse != null) {
            return Proxies.enhanceResponse(fatalErrorResponse);
        }

        requestCompliance.makeRequestCompliant(request);
        request.addHeader("Via",via);

        flushEntriesInvalidatedByRequest(route.getTargetHost(), request);

        if (!cacheableRequestPolicy.isServableFromCache(request)) {
            log.debug("Request is not servable from cache");
            return callBackend(route, request, context, execAware);
        }

        HttpCacheEntry entry = satisfyFromCache(target, request);
        if (entry == null) {
            log.debug("Cache miss");
            return handleCacheMiss(route, request, context, execAware);
        } else {
            return handleCacheHit(route, request, context, execAware, entry);
        }
    }

    private CloseableHttpResponse handleCacheHit(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware,
            final HttpCacheEntry entry) throws IOException, HttpException {
        HttpHost target = route.getTargetHost();
        recordCacheHit(target, request);
        CloseableHttpResponse out = null;
        Date now = getCurrentDate();
        if (suitabilityChecker.canCachedResponseBeUsed(target, request, entry, now)) {
            log.debug("Cache hit");
            out = Proxies.enhanceResponse(generateCachedResponse(request, context, entry, now));
        } else if (!mayCallBackend(request)) {
            log.debug("Cache entry not suitable but only-if-cached requested");
            out = Proxies.enhanceResponse(generateGatewayTimeout(context));
        } else if (validityPolicy.isRevalidatable(entry)
                && !(entry.getStatusCode() == HttpStatus.SC_NOT_MODIFIED
                && !suitabilityChecker.isConditional(request))) {
            log.debug("Revalidating cache entry");
            return revalidateCacheEntry(route, request, context, execAware, entry, now);
        } else {
            log.debug("Cache entry not usable; calling backend");
            return callBackend(route, request, context, execAware);
        }
        context.setAttribute(ClientContext.ROUTE, route);
        context.setAttribute(ExecutionContext.HTTP_TARGET_HOST, target);
        context.setAttribute(ExecutionContext.HTTP_REQUEST, request);
        context.setAttribute(ExecutionContext.HTTP_RESPONSE, out);
        context.setAttribute(ExecutionContext.HTTP_REQ_SENT, Boolean.TRUE);
        return out;
    }

    private CloseableHttpResponse revalidateCacheEntry(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware,
            final HttpCacheEntry entry,
            final Date now) throws IOException, HttpException {

        try {
            if (asynchRevalidator != null
                && !staleResponseNotAllowed(request, entry, now)
                && validityPolicy.mayReturnStaleWhileRevalidating(entry, now)) {
                log.trace("Serving stale with asynchronous revalidation");
                HttpResponse resp = generateCachedResponse(request, context, entry, now);
                asynchRevalidator.revalidateCacheEntry(route, request, context, execAware, entry);
                return Proxies.enhanceResponse(resp);
            }
            return revalidateCacheEntry(route, request, context, execAware, entry);
        } catch (IOException ioex) {
            return Proxies.enhanceResponse(
                    handleRevalidationFailure(request, context, entry, now));
        }
    }

    private CloseableHttpResponse handleCacheMiss(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware) throws IOException, HttpException {
        HttpHost target = route.getTargetHost();
        recordCacheMiss(target, request);

        if (!mayCallBackend(request)) {
            return Proxies.enhanceResponse(
                    new BasicHttpResponse(
                            HttpVersion.HTTP_1_1, HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout"));
        }

        Map<String, Variant> variants = getExistingCacheVariants(target, request);
        if (variants != null && variants.size() > 0) {
            return Proxies.enhanceResponse(
                    negotiateResponseFromVariants(route, request, context, execAware, variants));
        }

        return callBackend(route, request, context, execAware);
    }

    private HttpCacheEntry satisfyFromCache(
            final HttpHost target, final HttpRequestWrapper request) {
        HttpCacheEntry entry = null;
        try {
            entry = responseCache.getCacheEntry(target, request);
        } catch (IOException ioe) {
            log.warn("Unable to retrieve entries from cache", ioe);
        }
        return entry;
    }

    private HttpResponse getFatallyNoncompliantResponse(
            final HttpRequestWrapper request,
            final HttpContext context) {
        HttpResponse fatalErrorResponse = null;
        List<RequestProtocolError> fatalError = requestCompliance.requestIsFatallyNonCompliant(request);

        for (RequestProtocolError error : fatalError) {
            setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
            fatalErrorResponse = requestCompliance.getErrorForRequest(error);
        }
        return fatalErrorResponse;
    }

    private Map<String, Variant> getExistingCacheVariants(
            final HttpHost target,
            final HttpRequestWrapper request) {
        Map<String,Variant> variants = null;
        try {
            variants = responseCache.getVariantCacheEntriesWithEtags(target, request);
        } catch (IOException ioe) {
            log.warn("Unable to retrieve variant entries from cache", ioe);
        }
        return variants;
    }

    private void recordCacheMiss(final HttpHost target, final HttpRequestWrapper request) {
        cacheMisses.getAndIncrement();
        if (log.isTraceEnabled()) {
            RequestLine rl = request.getRequestLine();
            log.trace("Cache miss [host: " + target + "; uri: " + rl.getUri() + "]");
        }
    }

    private void recordCacheHit(final HttpHost target, final HttpRequestWrapper request) {
        cacheHits.getAndIncrement();
        if (log.isTraceEnabled()) {
            RequestLine rl = request.getRequestLine();
            log.trace("Cache hit [host: " + target + "; uri: " + rl.getUri() + "]");
        }
    }

    private void recordCacheUpdate(HttpContext context) {
        cacheUpdates.getAndIncrement();
        setResponseStatus(context, CacheResponseStatus.VALIDATED);
    }

    private void flushEntriesInvalidatedByRequest(
            final HttpHost target,
            final HttpRequestWrapper request) {
        try {
            responseCache.flushInvalidatedCacheEntriesFor(target, request);
        } catch (IOException ioe) {
            log.warn("Unable to flush invalidated entries from cache", ioe);
        }
    }

    private HttpResponse generateCachedResponse(HttpRequestWrapper request,
            HttpContext context, HttpCacheEntry entry, Date now) {
        final HttpResponse cachedResponse;
        if (request.containsHeader(HeaderConstants.IF_NONE_MATCH)
                || request.containsHeader(HeaderConstants.IF_MODIFIED_SINCE)) {
            cachedResponse = responseGenerator.generateNotModifiedResponse(entry);
        } else {
            cachedResponse = responseGenerator.generateResponse(entry);
        }
        setResponseStatus(context, CacheResponseStatus.CACHE_HIT);
        if (validityPolicy.getStalenessSecs(entry, now) > 0L) {
            cachedResponse.addHeader(HeaderConstants.WARNING,"110 localhost \"Response is stale\"");
        }
        return cachedResponse;
    }

    private HttpResponse handleRevalidationFailure(
            final HttpRequestWrapper request,
            final HttpContext context,
            final HttpCacheEntry entry,
            final Date now) {
        if (staleResponseNotAllowed(request, entry, now)) {
            return generateGatewayTimeout(context);
        } else {
            return unvalidatedCacheHit(context, entry);
        }
    }

    private HttpResponse generateGatewayTimeout(final HttpContext context) {
        setResponseStatus(context, CacheResponseStatus.CACHE_MODULE_RESPONSE);
        return new BasicHttpResponse(HttpVersion.HTTP_1_1,
                HttpStatus.SC_GATEWAY_TIMEOUT, "Gateway Timeout");
    }

    private HttpResponse unvalidatedCacheHit(
            final HttpContext context, final HttpCacheEntry entry) {
        final HttpResponse cachedResponse = responseGenerator.generateResponse(entry);
        setResponseStatus(context, CacheResponseStatus.CACHE_HIT);
        cachedResponse.addHeader(HeaderConstants.WARNING, "111 localhost \"Revalidation failed\"");
        return cachedResponse;
    }

    private boolean staleResponseNotAllowed(
            final HttpRequestWrapper request,
            final HttpCacheEntry entry,
            final Date now) {
        return validityPolicy.mustRevalidate(entry)
            || (cacheConfig.isSharedCache() && validityPolicy.proxyRevalidate(entry))
            || explicitFreshnessRequest(request, entry, now);
    }

    private boolean mayCallBackend(final HttpRequestWrapper request) {
        for (Header h: request.getHeaders(HeaderConstants.CACHE_CONTROL)) {
            for (HeaderElement elt : h.getElements()) {
                if ("only-if-cached".equals(elt.getName())) {
                    log.trace("Request marked only-if-cached");
                    return false;
                }
            }
        }
        return true;
    }

    private boolean explicitFreshnessRequest(
            final HttpRequestWrapper request,
            final HttpCacheEntry entry,
            final Date now) {
        for(Header h : request.getHeaders(HeaderConstants.CACHE_CONTROL)) {
            for(HeaderElement elt : h.getElements()) {
                if (HeaderConstants.CACHE_CONTROL_MAX_STALE.equals(elt.getName())) {
                    try {
                        int maxstale = Integer.parseInt(elt.getValue());
                        long age = validityPolicy.getCurrentAgeSecs(entry, now);
                        long lifetime = validityPolicy.getFreshnessLifetimeSecs(entry);
                        if (age - lifetime > maxstale) return true;
                    } catch (NumberFormatException nfe) {
                        return true;
                    }
                } else if (HeaderConstants.CACHE_CONTROL_MIN_FRESH.equals(elt.getName())
                            || HeaderConstants.CACHE_CONTROL_MAX_AGE.equals(elt.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String generateViaHeader(final HttpMessage msg) {

        final ProtocolVersion pv = msg.getProtocolVersion();
        String existingEntry = viaHeaders.get(pv);
        if (existingEntry != null) return existingEntry;

        final VersionInfo vi = VersionInfo.loadVersionInfo("org.apache.http.client", getClass().getClassLoader());
        final String release = (vi != null) ? vi.getRelease() : VersionInfo.UNAVAILABLE;

        String value;
        if ("http".equalsIgnoreCase(pv.getProtocol())) {
            value = String.format("%d.%d localhost (Apache-HttpClient/%s (cache))", pv.getMajor(), pv.getMinor(),
                    release);
        } else {
            value = String.format("%s/%d.%d localhost (Apache-HttpClient/%s (cache))", pv.getProtocol(), pv.getMajor(),
                    pv.getMinor(), release);
        }
        viaHeaders.put(pv, value);

        return value;
    }

    private void setResponseStatus(final HttpContext context, final CacheResponseStatus value) {
        if (context != null) {
            context.setAttribute(HttpCacheContext.CACHE_RESPONSE_STATUS, value);
        }
    }

    /**
     * Reports whether this {@code CachingHttpClient} implementation
     * supports byte-range requests as specified by the {@code Range}
     * and {@code Content-Range} headers.
     * @return {@code true} if byte-range requests are supported
     */
    public boolean supportsRangeAndContentRangeHeaders() {
        return SUPPORTS_RANGE_AND_CONTENT_RANGE_HEADERS;
    }

    Date getCurrentDate() {
        return new Date();
    }

    boolean clientRequestsOurOptions(final HttpRequest request) {
        RequestLine line = request.getRequestLine();

        if (!HeaderConstants.OPTIONS_METHOD.equals(line.getMethod()))
            return false;

        if (!"*".equals(line.getUri()))
            return false;

        if (!"0".equals(request.getFirstHeader(HeaderConstants.MAX_FORWARDS).getValue()))
            return false;

        return true;
    }

    CloseableHttpResponse callBackend(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware) throws IOException, HttpException  {

        Date requestDate = getCurrentDate();

        log.trace("Calling the backend");
        CloseableHttpResponse backendResponse = backend.execute(route, request, context, execAware);
        try {
            backendResponse.addHeader("Via", generateViaHeader(backendResponse));
            return handleBackendResponse(route, request, context, execAware,
                    requestDate, getCurrentDate(), backendResponse);
        } catch (IOException ex) {
            backendResponse.close();
            throw ex;
        } catch (RuntimeException ex) {
            backendResponse.close();
            throw ex;
        }
    }

    private boolean revalidationResponseIsTooOld(HttpResponse backendResponse,
            HttpCacheEntry cacheEntry) {
        final Header entryDateHeader = cacheEntry.getFirstHeader(HTTP.DATE_HEADER);
        final Header responseDateHeader = backendResponse.getFirstHeader(HTTP.DATE_HEADER);
        if (entryDateHeader != null && responseDateHeader != null) {
            try {
                Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
                Date respDate = DateUtils.parseDate(responseDateHeader.getValue());
                if (respDate.before(entryDate)) return true;
            } catch (DateParseException e) {
                // either backend response or cached entry did not have a valid
                // Date header, so we can't tell if they are out of order
                // according to the origin clock; thus we can skip the
                // unconditional retry recommended in 13.2.6 of RFC 2616.
            }
        }
        return false;
    }

    HttpResponse negotiateResponseFromVariants(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware,
            final Map<String, Variant> variants) throws IOException, HttpException {
        HttpRequestWrapper conditionalRequest = conditionalRequestBuilder
            .buildConditionalRequestFromVariants(request, variants);

        Date requestDate = getCurrentDate();
        CloseableHttpResponse backendResponse = backend.execute(
                route, conditionalRequest, context, execAware);
        try {
            Date responseDate = getCurrentDate();

            backendResponse.addHeader("Via", generateViaHeader(backendResponse));

            if (backendResponse.getStatusLine().getStatusCode() != HttpStatus.SC_NOT_MODIFIED) {
                return handleBackendResponse(
                        route, request, context, execAware,
                        requestDate, responseDate, backendResponse);
            }

            Header resultEtagHeader = backendResponse.getFirstHeader(HeaderConstants.ETAG);
            if (resultEtagHeader == null) {
                log.warn("304 response did not contain ETag");
                EntityUtils.consume(backendResponse.getEntity());
                backendResponse.close();
                return callBackend(route, request, context, execAware);
            }

            String resultEtag = resultEtagHeader.getValue();
            Variant matchingVariant = variants.get(resultEtag);
            if (matchingVariant == null) {
                log.debug("304 response did not contain ETag matching one sent in If-None-Match");
                EntityUtils.consume(backendResponse.getEntity());
                backendResponse.close();
                return callBackend(route, request, context, execAware);
            }

            HttpCacheEntry matchedEntry = matchingVariant.getEntry();

            if (revalidationResponseIsTooOld(backendResponse, matchedEntry)) {
                EntityUtils.consume(backendResponse.getEntity());
                backendResponse.close();
                return retryRequestUnconditionally(route, request, context, execAware, matchedEntry);
            }

            recordCacheUpdate(context);

            HttpCacheEntry responseEntry = getUpdatedVariantEntry(
                    route.getTargetHost(), conditionalRequest, requestDate, responseDate,
                    backendResponse, matchingVariant, matchedEntry);
            backendResponse.close();

            HttpResponse resp = responseGenerator.generateResponse(responseEntry);
            tryToUpdateVariantMap(route.getTargetHost(), request, matchingVariant);

            if (shouldSendNotModifiedResponse(request, responseEntry)) {
                return responseGenerator.generateNotModifiedResponse(responseEntry);
            }
            return resp;
        } catch (IOException ex) {
            backendResponse.close();
            throw ex;
        } catch (RuntimeException ex) {
            backendResponse.close();
            throw ex;
        }
    }

    private CloseableHttpResponse retryRequestUnconditionally(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware,
            final HttpCacheEntry matchedEntry) throws IOException, HttpException {
        HttpRequestWrapper unconditional = conditionalRequestBuilder
            .buildUnconditionalRequest(request, matchedEntry);
        return callBackend(route, unconditional, context, execAware);
    }

    private HttpCacheEntry getUpdatedVariantEntry(
            final HttpHost target,
            final HttpRequestWrapper conditionalRequest,
            final Date requestDate,
            final Date responseDate,
            final CloseableHttpResponse backendResponse,
            final Variant matchingVariant,
            final HttpCacheEntry matchedEntry) throws IOException {
        HttpCacheEntry responseEntry = matchedEntry;
        try {
            responseEntry = responseCache.updateVariantCacheEntry(target, conditionalRequest,
                    matchedEntry, backendResponse, requestDate, responseDate, matchingVariant.getCacheKey());
        } catch (IOException ioe) {
            log.warn("Could not update cache entry", ioe);
        } finally {
            backendResponse.close();
        }
        return responseEntry;
    }

    private void tryToUpdateVariantMap(
            final HttpHost target,
            final HttpRequestWrapper request,
            final Variant matchingVariant) {
        try {
            responseCache.reuseVariantEntryFor(target, request, matchingVariant);
        } catch (IOException ioe) {
            log.warn("Could not update cache entry to reuse variant", ioe);
        }
    }

    private boolean shouldSendNotModifiedResponse(
            final HttpRequestWrapper request,
            final HttpCacheEntry responseEntry) {
        return (suitabilityChecker.isConditional(request)
                && suitabilityChecker.allConditionalsMatch(request, responseEntry, new Date()));
    }

    CloseableHttpResponse revalidateCacheEntry(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware,
            final HttpCacheEntry cacheEntry) throws IOException, HttpException {

        HttpRequestWrapper conditionalRequest = conditionalRequestBuilder.buildConditionalRequest(request, cacheEntry);

        Date requestDate = getCurrentDate();
        CloseableHttpResponse backendResponse = backend.execute(
                route, conditionalRequest, context, execAware);
        Date responseDate = getCurrentDate();

        if (revalidationResponseIsTooOld(backendResponse, cacheEntry)) {
            backendResponse.close();
            HttpRequestWrapper unconditional = conditionalRequestBuilder
                .buildUnconditionalRequest(request, cacheEntry);
            requestDate = getCurrentDate();
            backendResponse = backend.execute(route, unconditional, context, execAware);
            responseDate = getCurrentDate();
        }

        backendResponse.addHeader(HeaderConstants.VIA, generateViaHeader(backendResponse));

        int statusCode = backendResponse.getStatusLine().getStatusCode();
        if (statusCode == HttpStatus.SC_NOT_MODIFIED || statusCode == HttpStatus.SC_OK) {
            recordCacheUpdate(context);
        }

        if (statusCode == HttpStatus.SC_NOT_MODIFIED) {
            HttpCacheEntry updatedEntry = responseCache.updateCacheEntry(
                    route.getTargetHost(), request, cacheEntry,
                    backendResponse, requestDate, responseDate);
            if (suitabilityChecker.isConditional(request)
                    && suitabilityChecker.allConditionalsMatch(request, updatedEntry, new Date())) {
                return Proxies.enhanceResponse(
                        responseGenerator.generateNotModifiedResponse(updatedEntry));
            }
            return Proxies.enhanceResponse(responseGenerator.generateResponse(updatedEntry));
        }

        if (staleIfErrorAppliesTo(statusCode)
            && !staleResponseNotAllowed(request, cacheEntry, getCurrentDate())
            && validityPolicy.mayReturnStaleIfError(request, cacheEntry, responseDate)) {
            try {
                HttpResponse cachedResponse = responseGenerator.generateResponse(cacheEntry);
                cachedResponse.addHeader(HeaderConstants.WARNING, "110 localhost \"Response is stale\"");
                return Proxies.enhanceResponse(cachedResponse);
            } finally {
                backendResponse.close();
            }
        }
        return handleBackendResponse(
                route, conditionalRequest, context, execAware,
                requestDate, responseDate, backendResponse);
    }

    private boolean staleIfErrorAppliesTo(int statusCode) {
        return statusCode == HttpStatus.SC_INTERNAL_SERVER_ERROR
                || statusCode == HttpStatus.SC_BAD_GATEWAY
                || statusCode == HttpStatus.SC_SERVICE_UNAVAILABLE
                || statusCode == HttpStatus.SC_GATEWAY_TIMEOUT;
    }

    CloseableHttpResponse handleBackendResponse(
            final HttpRoute route,
            final HttpRequestWrapper request,
            final HttpClientContext context,
            final HttpExecutionAware execAware,
            final Date requestDate,
            final Date responseDate,
            final CloseableHttpResponse backendResponse) throws IOException {

        log.trace("Handling Backend response");
        responseCompliance.ensureProtocolCompliance(request, backendResponse);

        HttpHost target = route.getTargetHost();
        boolean cacheable = responseCachingPolicy.isResponseCacheable(request, backendResponse);
        responseCache.flushInvalidatedCacheEntriesFor(target, request, backendResponse);
        if (cacheable && !alreadyHaveNewerCacheEntry(target, request, backendResponse)) {
            try {
                return Proxies.enhanceResponse(responseCache.cacheAndReturnResponse(
                        target, request, backendResponse, requestDate, responseDate));
            } finally {
                backendResponse.close();
            }
        }
        if (!cacheable) {
            try {
                responseCache.flushCacheEntriesFor(target, request);
            } catch (IOException ioe) {
                log.warn("Unable to flush invalid cache entries", ioe);
            }
        }
        return backendResponse;
    }

    private boolean alreadyHaveNewerCacheEntry(HttpHost target, HttpRequestWrapper request,
            HttpResponse backendResponse) {
        HttpCacheEntry existing = null;
        try {
            existing = responseCache.getCacheEntry(target, request);
        } catch (IOException ioe) {
            // nop
        }
        if (existing == null) return false;
        Header entryDateHeader = existing.getFirstHeader(HTTP.DATE_HEADER);
        if (entryDateHeader == null) return false;
        Header responseDateHeader = backendResponse.getFirstHeader(HTTP.DATE_HEADER);
        if (responseDateHeader == null) return false;
        try {
            Date entryDate = DateUtils.parseDate(entryDateHeader.getValue());
            Date responseDate = DateUtils.parseDate(responseDateHeader.getValue());
            return responseDate.before(entryDate);
        } catch (DateParseException e) {
            // Empty on Purpose
        }
        return false;
    }

}
