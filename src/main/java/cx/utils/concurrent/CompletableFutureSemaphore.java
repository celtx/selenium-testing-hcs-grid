/*
 * Copyright (c) Celtx Inc. <https://www.celtx.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cx.utils.concurrent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public class CompletableFutureSemaphore {

    private Logger logger = Logger.getLogger(CompletableFutureSemaphore.class.getName());

    final private long limit;
    final private AtomicLong tokens;
    final private List<CompletableFuture<CompletableFutureSemaphore>> waiting;

    public CompletableFutureSemaphore(long tokens) {
        this.limit = tokens;
        this.tokens = new AtomicLong(tokens);
        this.waiting = Collections.synchronizedList(new ArrayList<>());
    }

    public synchronized CompletableFuture<CompletableFutureSemaphore> acquire() {
        CompletableFuture<CompletableFutureSemaphore> cf = new CompletableFuture<>();

        long currTokens = this.tokens.get();
        if (currTokens < 0) {
            String msg = String.format("tokens = %d is less than zero", currTokens);
            logger.severe(msg);
            cf.completeExceptionally(new IllegalStateException(msg));
            return cf;
        }

        if (currTokens < 1) {
            this.waiting.add(cf);
        } else {
            this.tokens.decrementAndGet();
            cf.complete(this);
        }

        logger.fine(String.format("tokens=%d waiting=%d", this.tokens.get(), this.waiting.size()));

        return cf;
    }

    public synchronized CompletableFuture<CompletableFutureSemaphore> release() {
        CompletableFuture<CompletableFutureSemaphore> cf;

        long currTokens = this.tokens.get();
        if (currTokens >= this.limit) {
            String msg = String.format("tokens %d should be less than limit before release %d", currTokens, limit);
            logger.severe(msg);
            cf = new CompletableFuture<>();
            cf.completeExceptionally(new IllegalStateException(msg));
            return cf;
        }

        this.tokens.incrementAndGet();
        if (this.waiting.size() > 0) {
            this.tokens.decrementAndGet();
            cf = this.waiting.remove(0);
        } else {
            cf = new CompletableFuture<>();
        }
        logger.fine(String.format("tokens=%d waiting=%d", this.tokens.get(), this.waiting.size()));
        cf.complete(this);

        return cf;
    }
}
