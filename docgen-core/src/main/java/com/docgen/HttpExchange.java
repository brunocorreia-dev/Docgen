package com.docgen;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Enforces an end-to-end deadline that includes consumption of the response body. */
final class HttpExchange {
    private HttpExchange() {
    }

    static <T> HttpResponse<T> send(
            HttpClient client,
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            Duration deadline,
            String service
    ) throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<T>> exchange = client.sendAsync(request, handler);
        return await(exchange, deadline, service);
    }

    static <T> HttpResponse<T> await(
            CompletableFuture<HttpResponse<T>> exchange,
            Duration deadline,
            String service
    ) throws IOException, InterruptedException {
        if (exchange == null || deadline == null || deadline.isZero() || deadline.isNegative()) {
            throw new IllegalArgumentException("A live exchange and positive deadline are required");
        }
        String label = service == null || service.isBlank() ? "Provider" : service;
        try {
            return exchange.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            exchange.cancel(true);
            throw new HttpTimeoutException(label + " response exceeded its end-to-end deadline.");
        } catch (InterruptedException interrupted) {
            exchange.cancel(true);
            throw interrupted;
        } catch (ExecutionException failed) {
            exchange.cancel(true);
            Throwable cause = failed.getCause();
            String detail = SafeText.forTerminal(cause == null ? null : cause.getMessage());
            throw new IOException(label + " request failed: " + detail);
        }
    }
}
