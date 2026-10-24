package com.wxmyyds.discordautotranslate.hooks;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Weak map keyed by reference identity, not by data-class equality. */
final class IdentityMessageMap {

    private static final int MAX_ENTRIES = 8192;

    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<IdentityKey, String> values = new HashMap<>();

    synchronized void put(Object content, String messageId) {
        if (content == null || messageId == null) {
            return;
        }
        expungeCollected();
        IdentityKey key = new IdentityKey(content, collected);
        values.put(key, messageId);
        while (values.size() > MAX_ENTRIES) {
            Iterator<IdentityKey> it = values.keySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            it.next();
            it.remove();
        }
    }

    synchronized String get(Object content) {
        if (content == null) {
            return null;
        }
        expungeCollected();
        return values.get(new IdentityKey(content, null));
    }

    synchronized void clear() {
        values.clear();
        while (collected.poll() != null) {
            // Drain references from the retired generation.
        }
    }

    private void expungeCollected() {
        IdentityKey key;
        while ((key = (IdentityKey) collected.poll()) != null) {
            values.remove(key);
        }
    }

    private static final class IdentityKey extends WeakReference<Object> {
        private final int hash;

        IdentityKey(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            hash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof IdentityKey)) {
                return false;
            }
            Object mine = get();
            return mine != null && mine == ((IdentityKey) other).get();
        }
    }
}
