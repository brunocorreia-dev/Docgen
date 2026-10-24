package com.docgen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/**
 * Collects a provider response body with a byte cap enforced while it arrives,
 * so an oversized body is cancelled instead of materialized, and rejects bodies
 * that are not valid UTF-8. Failure messages never include body content.
 */
final class BoundedUtf8BodySubscriber implements HttpResponse.BodySubscriber<String> {
    private final int maxBytes;
    private final String service;
    private final ByteArrayOutputStream received;
    private final CompletableFuture<String> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private boolean finished;

    /** Accepts at most {@code maxBytes} of a successful body and decodes it as strict UTF-8. */
    static HttpResponse.BodySubscriber<String> of(int maxBytes, String service) {
        return new BoundedUtf8BodySubscriber(maxBytes, service);
    }

    private BoundedUtf8BodySubscriber(int maxBytes, String service) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
        this.service = service == null || service.isBlank() ? "Provider" : service;
        this.received = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
    }

    @Override
    public CompletionStage<String> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription newSubscription) {
        if (newSubscription == null) {
            fail(new IOException(service + " response body could not be read."));
            return;
        }
        if (subscription != null) {
            newSubscription.cancel();
            return;
        }
        subscription = newSubscription;
        if (finished) {
            newSubscription.cancel();
        } else {
            newSubscription.request(1);
        }
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (finished) {
            return;
        }
        if (buffers == null) {
            fail(new IOException(service + " response body could not be read."));
            return;
        }

        long incomingBytes = 0;
        for (ByteBuffer buffer : buffers) {
            if (buffer == null) {
                fail(new IOException(service + " response body could not be read."));
                return;
            }
            incomingBytes += buffer.remaining();
            if (incomingBytes > maxBytes - received.size()) {
                fail(new IOException(service + " response exceeded the byte safety limit."));
                return;
            }
        }

        byte[] chunk = new byte[8192];
        for (ByteBuffer buffer : buffers) {
            ByteBuffer copy = buffer.slice();
            while (copy.hasRemaining()) {
                int length = Math.min(copy.remaining(), chunk.length);
                copy.get(chunk, 0, length);
                received.write(chunk, 0, length);
            }
        }
        if (subscription != null) {
            subscription.request(1);
        }
    }

    @Override
    public void onError(Throwable ignored) {
        fail(new IOException(service + " response body could not be read."));
    }

    @Override
    public void onComplete() {
        if (finished) {
            return;
        }
        finished = true;
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(received.toByteArray()))
                    .toString();
            body.complete(decoded);
        } catch (CharacterCodingException ignored) {
            body.completeExceptionally(new IOException(service + " returned a non-UTF-8 response body."));
        }
    }

    private void fail(IOException error) {
        if (finished) {
            return;
        }
        finished = true;
        if (subscription != null) {
            subscription.cancel();
        }
        body.completeExceptionally(error);
    }
}
