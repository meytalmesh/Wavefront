package com.wavefront.agent.listeners;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.RateLimiter;

import com.wavefront.agent.Utils;
import com.wavefront.agent.auth.TokenAuthenticatorBuilder;
import com.wavefront.agent.auth.TokenValidationMethod;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.common.TraceConstants;
import com.wavefront.data.ReportableEntityType;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import wavefront.report.Annotation;
import wavefront.report.Span;
import zipkin2.SpanBytesDecoderDetector;
import zipkin2.codec.BytesDecoder;

import static com.wavefront.sdk.common.Constants.APPLICATION_TAG_KEY;
import static com.wavefront.sdk.common.Constants.CLUSTER_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SERVICE_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SHARD_TAG_KEY;
import static com.wavefront.sdk.common.Constants.SOURCE_KEY;

/**
 * Handler that processes Zipkin trace data over HTTP and converts them to Wavefront format.
 *
 * @author Anil Kodali (akodali@vmware.com)
 */
public class ZipkinPortUnificationHandler extends PortUnificationHandler {
  private static final Logger logger = Logger.getLogger(
      ZipkinPortUnificationHandler.class.getCanonicalName());
  private final String handle;
  private final ReportableEntityHandler<Span> handler;
  private final AtomicBoolean traceDisabled;
  private final RateLimiter warningLoggerRateLimiter = RateLimiter.create(0.2);
  private final Counter discardedBatches;
  private final Counter processedBatches;
  private final Counter failedBatches;

  private final static Set<String> ZIPKIN_VALID_PATHS = ImmutableSet.of(
      "/api/v1/spans/",
      "/api/v2/spans/");
  private final static String ZIPKIN_VALID_HTTP_METHOD = "POST";
  private final static String DEFAULT_APPLICATION = "Zipkin";
  private final static String DEFAULT_SOURCE = "zipkin";
  private final static String DEFAULT_SERVICE = "unknown";
  private final static String DEFAULT_SPAN_NAME = "unknown";

  private static final Logger zipkinDataLogger = Logger.getLogger("ZipkinDataLogger");

  public ZipkinPortUnificationHandler(String handle,
                                      ReportableEntityHandlerFactory handlerFactory,
                                      AtomicBoolean traceDisabled) {
    this(handle,
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE, handle)),
        traceDisabled);
  }

  public ZipkinPortUnificationHandler(final String handle,
                                      ReportableEntityHandler<Span> handler,
                                      AtomicBoolean traceDisabled) {
    super(TokenAuthenticatorBuilder.create().setTokenValidationMethod(TokenValidationMethod.NONE).build(),
        handle, false, true);
    this.handle = handle;
    this.handler = handler;
    this.traceDisabled = traceDisabled;
    this.discardedBatches = Metrics.newCounter(new MetricName(
        "spans." + handle + ".batches", "", "discarded"));
    this.processedBatches = Metrics.newCounter(new MetricName(
        "spans." + handle + ".batches", "", "processed"));
    this.failedBatches = Metrics.newCounter(new MetricName(
        "spans." + handle + ".batches", "", "failed"));
  }

  @Override
  protected void handleHttpMessage(final ChannelHandlerContext ctx,
                                   final FullHttpRequest incomingRequest) {
    URI uri = parseUri(ctx, incomingRequest);
    if (uri == null) return;

    String path = uri.getPath().endsWith("/") ? uri.getPath() : uri.getPath() + "/";

    // Validate Uri Path and HTTP method of incoming Zipkin spans.
    if (!ZIPKIN_VALID_PATHS.contains(path)) {
      writeHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Unsupported URL path.", incomingRequest);
      logWarning("WF-400: Requested URI path '" + path + "' is not supported.", null, ctx);
      return;
    }
    if (!incomingRequest.method().toString().equalsIgnoreCase(ZIPKIN_VALID_HTTP_METHOD)) {
      writeHttpResponse(ctx, HttpResponseStatus.BAD_REQUEST, "Unsupported Http method.", incomingRequest);
      logWarning("WF-400: Requested http method '" + incomingRequest.method().toString() +
          "' is not supported.", null, ctx);
      return;
    }

    HttpResponseStatus status;
    StringBuilder output = new StringBuilder();

    // Handle case when tracing is disabled, ignore reported spans.
    if (traceDisabled.get()) {
      if (warningLoggerRateLimiter.tryAcquire()) {
        logger.info("Ingested spans discarded because tracing feature is not enabled on the " +
            "server");
      }
      discardedBatches.inc();
      output.append("Ingested spans discarded because tracing feature is not enabled on the " +
          "server.");
      status = HttpResponseStatus.ACCEPTED;
      writeHttpResponse(ctx, status, output, incomingRequest);
      return;
    }


    try {
      byte[] bytesArray = new byte[incomingRequest.content().nioBuffer().remaining()];
      incomingRequest.content().nioBuffer().get(bytesArray, 0, bytesArray.length);
      BytesDecoder<zipkin2.Span> decoder = SpanBytesDecoderDetector.decoderForListMessage(bytesArray);
      List<zipkin2.Span> zipkinSpanSink = new ArrayList<>();
      decoder.decodeList(bytesArray, zipkinSpanSink);
      processZipkinSpans(zipkinSpanSink);
      status = HttpResponseStatus.ACCEPTED;
      processedBatches.inc();
    } catch (Exception e) {
      failedBatches.inc();
      writeExceptionText(e, output);
      status = HttpResponseStatus.BAD_REQUEST;
      logger.log(Level.WARNING, "Zipkin batch processing failed", Throwables.getRootCause(e));
    }
    writeHttpResponse(ctx, status, output, incomingRequest);
  }

  private void processZipkinSpans(List<zipkin2.Span> zipkinSpans) {
    for (zipkin2.Span zipkinSpan : zipkinSpans) {
      processZipkinSpan(zipkinSpan);
    }
  }

  private void processZipkinSpan(zipkin2.Span zipkinSpan) {
    // Add application tags, span references , span kind and http uri, responses etc.
    List<Annotation> annotations = addAnnotations(zipkinSpan);

    /** Add source of the span following the below:
     *    1. If "source" is provided by span tags , use it else
     *    2. Set "source" to local service endpoint's ipv4 address, else
     *    3. Default "source" to "zipkin".
     */
    String sourceName = DEFAULT_SOURCE;
    if (zipkinSpan.tags() != null && zipkinSpan.tags().size() > 0) {
      if (zipkinSpan.tags().get(SOURCE_KEY) != null) {
        sourceName = zipkinSpan.tags().get(SOURCE_KEY);
      } else if (zipkinSpan.localEndpoint() != null && zipkinSpan.localEndpoint().ipv4() != null) {
        sourceName = zipkinSpan.localEndpoint().ipv4();
      }
    }
    // Set spanName.
    String spanName = zipkinSpan.name() == null ? DEFAULT_SPAN_NAME : zipkinSpan.name();
    //Build wavefront span
    Span newSpan = Span.newBuilder().
        setCustomer("dummy").
        setName(spanName).
        setSource(sourceName).
        setSpanId(getSpanUuid(zipkinSpan)).
        setTraceId(Utils.convertToUuidString(zipkinSpan.traceId())).
        setStartMillis(zipkinSpan.timestampAsLong() / 1000).
        setDuration(zipkinSpan.durationAsLong() / 1000).
        setAnnotations(annotations).
        build();

    // Log Zipkin spans as well as Wavefront spans for debugging purposes.
    if (zipkinDataLogger.isLoggable(Level.FINEST)) {
      zipkinDataLogger.info("Inbound Zipkin span: " + zipkinSpan.toString());
      zipkinDataLogger.info("Converted Wavefront span: " + newSpan.toString());
    }

    handler.report(newSpan);
  }

  private List<Annotation> addAnnotations(zipkin2.Span zipkinSpan) {
    List<Annotation> annotations = new ArrayList<>();
    // Set Span's Application Tags.
    addTagWithKey(zipkinSpan, annotations, APPLICATION_TAG_KEY, DEFAULT_APPLICATION);
    addTagWithKey(zipkinSpan, annotations, CLUSTER_TAG_KEY, null);
    addTagWithKey(zipkinSpan, annotations, SHARD_TAG_KEY, null);

    // Set Span's References.
    if (zipkinSpan.parentId() != null) {
      annotations.add(new Annotation(TraceConstants.PARENT_KEY, Utils.convertToUuidString(zipkinSpan
          .parentId())));
    }

    // Set Span Kind.
    if (zipkinSpan.kind() != null) {
      annotations.add(new Annotation("span.kind", zipkinSpan.kind().toString().toLowerCase()));
    }

    //Set Span's service name.
    String serviceName = zipkinSpan.localServiceName() == null ? DEFAULT_SERVICE :
        zipkinSpan.localServiceName();
    annotations.add(new Annotation(SERVICE_TAG_KEY, serviceName));

    // Set Span Http Tags.
    // TODO: Check if these tags are required for a span with span.kind = client.
    if (zipkinSpan.kind() != null && !zipkinSpan.kind().toString().equalsIgnoreCase("client")) {
      addTagWithKey(zipkinSpan, annotations, "http.method", null);
      addTagWithKey(zipkinSpan, annotations, "http.url", null);
      addTagWithKey(zipkinSpan, annotations, "http.status_code", null);
    }
    return annotations;
  }

  private static void addTagWithKey(zipkin2.Span zipkinSpan,
                                    List<Annotation> annotations,
                                    String key,
                                    String defaultValue) {
    if (zipkinSpan.tags() != null && zipkinSpan.tags().size() > 0 && zipkinSpan.tags().get(key) != null) {
      annotations.add(new Annotation(key, zipkinSpan.tags().get(key)));
    } else if (defaultValue != null) {
      annotations.add(new Annotation(key, defaultValue));
    }
  }

  private static String getSpanUuid(zipkin2.Span zipkinSpan) {

    /**
     * Handle span Id's in Zipkin to separate client and server spans and comply with wavefront format.
     * Otherwise, A Zipkin span with span.kind as "client" or "server", both share the same span
     * Id and both won't be shown in the tracing UI.
     */
    if (zipkinSpan.kind().toString().equalsIgnoreCase("client")) {
      return createAlternateSpanId(zipkinSpan.id());
    }
    return Utils.convertToUuidString(zipkinSpan.id());
  }

  /**
   * Method to create alternate wavefront spanId for client spans assuming zipkin spanId is Encoded
   * as 16 lowercase hex characters.
   *
   * Ex: clients zipkin spanId = Encoded as 16 digit hex (Ex: 2822889fe47043bd) clients wavefront
   * spanUuId = randomly generated 16 digit hex + zipkin spanId
   */
  private static String createAlternateSpanId(String zipkinSpanId) {
    String spanUuid;
    // Returned in format <8-4-4>.
    String mostSig = UUID.randomUUID().toString().substring(0, 18);

    spanUuid = mostSig + "-" +
        zipkinSpanId.substring(0, 4) + "-" +
        zipkinSpanId.substring(4, 16);
    return spanUuid;
  }

  @Override
  protected void processLine(final ChannelHandlerContext ctx, final String message) {
    throw new UnsupportedOperationException("Invalid context for processLine");
  }
}

