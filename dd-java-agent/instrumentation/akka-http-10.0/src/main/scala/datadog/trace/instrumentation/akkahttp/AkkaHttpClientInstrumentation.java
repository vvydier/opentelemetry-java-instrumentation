package datadog.trace.instrumentation.akkahttp;

import static io.opentracing.log.Fields.ERROR_OBJECT;
import static net.bytebuddy.matcher.ElementMatchers.*;

import akka.NotUsed;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.scaladsl.HttpExt;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import akka.stream.scaladsl.Flow;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.*;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.Tuple2;
import scala.concurrent.Future;
import scala.runtime.AbstractFunction1;
import scala.util.Try;

@Slf4j
@AutoService(Instrumenter.class)
public final class AkkaHttpClientInstrumentation extends Instrumenter.Default {
  public AkkaHttpClientInstrumentation() {
    super("akka-http", "akka-http-client");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("akka.http.scaladsl.HttpExt");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      AkkaHttpClientInstrumentation.class.getName() + "$OnCompleteHandler",
      AkkaHttpClientInstrumentation.class.getName() + "$AkkaHttpHeaders",
      AkkaHttpClientTransformFlow.class.getName(),
      AkkaHttpClientTransformFlow.class.getName() + "$",
      AkkaHttpClientTransformFlow.class.getName() + "$$anonfun$transform$1",
      AkkaHttpClientTransformFlow.class.getName() + "$$anonfun$transform$2",
    };
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    final Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(
        named("singleRequest").and(takesArgument(0, named("akka.http.scaladsl.model.HttpRequest"))),
        SingleRequestAdvice.class.getName());
    transformers.put(
        named("superPool").and(returns(named("akka.stream.scaladsl.Flow"))),
        SuperPoolAdvice.class.getName());
    return transformers;
  }

  public static class SingleRequestAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Scope methodEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpRequest request) {
      Scope scope =
          GlobalTracer.get()
              .buildSpan("akka-http.request")
              .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
              .withTag(Tags.HTTP_METHOD.getKey(), request.method().value())
              .withTag(DDTags.SPAN_TYPE, DDSpanTypes.HTTP_CLIENT)
              .withTag(Tags.COMPONENT.getKey(), "akka-http-client")
              .withTag(Tags.HTTP_URL.getKey(), request.getUri().toString())
              .startActive(false);

      AkkaHttpHeaders headers = new AkkaHttpHeaders(request);
      GlobalTracer.get().inject(scope.span().context(), Format.Builtin.HTTP_HEADERS, headers);
      // Request is immutable, so we have to assign new value once we update headers
      request = headers.getRequest();

      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void methodExit(
        @Advice.Argument(value = 0) final HttpRequest request,
        @Advice.This final HttpExt thiz,
        @Advice.Return final Future<HttpResponse> responseFuture,
        @Advice.Enter final Scope scope) {
      responseFuture.onComplete(new OnCompleteHandler(scope.span()), thiz.system().dispatcher());
      scope.close();
    }
  }

  public static class SuperPoolAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <T> void methodExit(
        @Advice.Return(readOnly = false)
            Flow<Tuple2<HttpRequest, T>, Tuple2<Try<HttpResponse>, T>, NotUsed> flow) {
      flow = AkkaHttpClientTransformFlow.transform(flow);
    }
  }

  public static class OnCompleteHandler extends AbstractFunction1<Try<HttpResponse>, Void> {
    private final Span span;

    public OnCompleteHandler(Span span) {
      this.span = span;
    }

    @Override
    public Void apply(Try<HttpResponse> result) {
      if (result.isSuccess()) {
        Tags.HTTP_STATUS.set(span, result.get().status().intValue());
      } else {
        Tags.ERROR.set(span, true);
        span.log(Collections.singletonMap(ERROR_OBJECT, result.failed().get()));
      }
      span.finish();
      return null;
    }
  }

  public static class AkkaHttpHeaders implements TextMap {
    private HttpRequest request;

    public AkkaHttpHeaders(HttpRequest request) {
      this.request = request;
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
      throw new UnsupportedOperationException(
          "This class should be used only with Tracer.inject()!");
    }

    @Override
    public void put(final String name, final String value) {
      // It looks like this cast is only needed in Java, Scala would have figured it out
      request = (HttpRequest) request.addHeader(RawHeader.create(name, value));
    }

    public HttpRequest getRequest() {
      return request;
    }
  }
}
