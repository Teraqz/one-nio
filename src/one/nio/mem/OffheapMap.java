package one.nio.mem;

import one.nio.async.AsyncExecutor;
import one.nio.async.ParallelTask;
import one.nio.lock.RWLock;
import one.nio.util.JavaInternals;
import one.nio.util.QuickSelect;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import sun.misc.Unsafe;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public abstract class OffheapMap<K, V> implements OffheapMapMXBean {
    protected static final Log log = LogFactory.getLog(OffheapMap.class);
    protected static final Unsafe unsafe = JavaInternals.getUnsafe();
    protected static final long byteArrayOffset = unsafe.arrayBaseOffset(byte[].class);
    protected static final long MB = 1024 * 1024;

    protected static final int CONCURRENCY_LEVEL = 65536;  // must be power of 2

    protected static final int HASH_OFFSET = 0;
    protected static final int NEXT_OFFSET = 8;
    protected static final int TIME_OFFSET = 16;
    protected static final int HEADER_SIZE = 24;

    protected final int capacity;
    protected final AtomicInteger count = new AtomicInteger();
    protected final AtomicLong expirations = new AtomicLong();
    protected final RWLock[] locks = createLocks();

    protected long mapBase;
    protected int[] ageHistogram = new int[Long.SIZE + 1];
    protected long timeToLive = Long.MAX_VALUE;
    protected long cleanupInterval = 60000;
    protected double cleanupThreshold;
    protected Thread cleanupThread;

    protected OffheapMap(int capacity) {
        this.capacity = (capacity + (CONCURRENCY_LEVEL - 1)) & ~(CONCURRENCY_LEVEL - 1);
    }

    protected OffheapMap(int capacity, long address) {
        this(capacity);
        this.mapBase = address != 0 ? address : DirectMemory.allocateAndFill(this.capacity * 8, this, (byte) 0);
    }

    private static RWLock[] createLocks() {
        RWLock[] locks = new RWLock[CONCURRENCY_LEVEL];
        for (int i = 0; i < CONCURRENCY_LEVEL; i++) {
            locks[i] = new RWLock();
        }
        return locks;
    }

    public void close() {
        if (cleanupThread != null) {
            try {
                cleanupThread.interrupt();
                cleanupThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public long getTimeToLive() {
        return timeToLive;
    }

    @Override
    public void setTimeToLive(long timeToLive) {
        this.timeToLive = timeToLive;
    }

    @Override
    public long getCleanupInterval() {
        return cleanupInterval;
    }

    @Override
    public void setCleanupInterval(long cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    @Override
    public double getCleanupThreshold() {
        return cleanupThreshold;
    }

    @Override
    public void setCleanupThreshold(double cleanupThreshold) {
        this.cleanupThreshold = cleanupThreshold;
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public int getCount() {
        return count.get();
    }

    @Override
    public long getExpirations() {
        return expirations.get();
    }

    @Override
    public int[] getAgeHistogram() {
        return ageHistogram;
    }

    public V get(K key) {
        long hashCode = hashCode(key);
        long currentPtr = bucketFor(hashCode);

        RWLock lock = lockFor(hashCode).lockRead();
        try {
            for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
                if (unsafe.getLong(entry + HASH_OFFSET) == hashCode && equalsAt(entry, key)) {
                    return isExpired(entry) ? null : valueAt(entry);
                }
            }
        } finally {
            lock.unlockRead();
        }

        return null;
    }

    public void put(K key, V value) {
        long hashCode = hashCode(key);
        long currentPtr = bucketFor(hashCode);
        int newSize = sizeOf(value);

        RWLock lock = lockFor(hashCode).lockWrite();
        try {
            for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
                if (unsafe.getLong(entry + HASH_OFFSET) == hashCode && equalsAt(entry, key)) {
                    int oldSize = sizeOf(entry);
                    if (newSize <= oldSize) {
                        setTimeAt(entry);
                        setValueAt(entry, value);
                        return;
                    }

                    unsafe.putAddress(currentPtr, unsafe.getAddress(entry + NEXT_OFFSET));
                    destroyEntry(entry);
                    count.decrementAndGet();
                    break;
                }
            }

            long entry = allocateEntry(key, hashCode, newSize);
            unsafe.putLong(entry + HASH_OFFSET, hashCode);
            unsafe.putAddress(entry + NEXT_OFFSET, unsafe.getAddress(currentPtr));
            setTimeAt(entry);
            setValueAt(entry, value);

            unsafe.putAddress(currentPtr, entry);
            count.incrementAndGet();
        } finally {
            lock.unlockWrite();
        }
    }

    public boolean putIfAbsent(K key, V value) {
        long hashCode = hashCode(key);
        long currentPtr = bucketFor(hashCode);

        RWLock lock = lockFor(hashCode).lockWrite();
        try {
            for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
                if (unsafe.getLong(entry + HASH_OFFSET) == hashCode && equalsAt(entry, key)) {
                    return false;
                }
            }

            long entry = allocateEntry(key, hashCode, sizeOf(value));
            unsafe.putLong(entry + HASH_OFFSET, hashCode);
            unsafe.putAddress(entry + NEXT_OFFSET, 0);
            setTimeAt(entry);
            setValueAt(entry, value);

            unsafe.putAddress(currentPtr, entry);
        } finally {
            lock.unlockWrite();
        }

        count.incrementAndGet();
        return true;
    }

    public boolean remove(K key) {
        long hashCode = hashCode(key);
        long currentPtr = bucketFor(hashCode);
        long entry;

        RWLock lock = lockFor(hashCode).lockWrite();
        try {
            for (;;) {
                if ((entry = unsafe.getAddress(currentPtr)) == 0) {
                    return false;
                }
                if (unsafe.getLong(entry + HASH_OFFSET) == hashCode && equalsAt(entry, key)) {
                    unsafe.putAddress(currentPtr, unsafe.getAddress(entry + NEXT_OFFSET));
                    break;
                }
                currentPtr = entry + NEXT_OFFSET;
            }
        } finally {
            lock.unlockWrite();
        }

        destroyEntry(entry);
        count.decrementAndGet();
        return true;
    }

    public void touch(K key) {
        long hashCode = hashCode(key);
        long currentPtr = bucketFor(hashCode);

        RWLock lock = lockFor(hashCode).lockRead();
        try {
            for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
                if (unsafe.getLong(entry + HASH_OFFSET) == hashCode && equalsAt(entry, key)) {
                    setTimeAt(entry);
                    return;
                }
            }
        } finally {
            lock.unlockRead();
        }
    }

    public Record<K, V> lockRecordForRead(K key) {
        long hashCode = hashCode(key);
        long currentPtr = bucketFor(hashCode);

        RWLock lock = lockFor(hashCode).lockRead();

        for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
            if (unsafe.getLong(entry + HASH_OFFSET) == hashCode && equalsAt(entry, key)) {
                if (isExpired(entry)) break;
                return new Record<K, V>(this, lock, entry);
            }
        }

        lock.unlockRead();
        return null;
    }

    public WritableRecord<K, V> lockRecordForWrite(K key, boolean create) {
        long hashCode = hashCode(key);
        long currentPtr = bucketFor(hashCode);

        RWLock lock = lockFor(hashCode).lockWrite();

        for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
            if (unsafe.getLong(entry + HASH_OFFSET) == hashCode && equalsAt(entry, key)) {
                return new WritableRecord<K, V>(this, lock, entry, key, currentPtr);
            }
        }

        if (create) {
            return new WritableRecord<K, V>(this, lock, 0, key, currentPtr);
        }

        lock.unlockWrite();
        return null;
    }

    public int entriesToClean() {
        return getCount() - (int) (getCapacity() * (1.0 - cleanupThreshold));
    }

    public int removeExpired(long expirationAge) {
        int expired = 0;
        int[] newHistogram = new int[Long.SIZE + 1];
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < CONCURRENCY_LEVEL; i++) {
            RWLock lock = locks[i].lockWrite();
            try {
                for (int j = i; j < capacity; j += CONCURRENCY_LEVEL) {
                    long currentPtr = mapBase + (long) j * 8;
                    long nextEntry;
                    for (long entry = unsafe.getAddress(currentPtr); entry != 0; entry = nextEntry) {
                        nextEntry = unsafe.getAddress(entry + NEXT_OFFSET);
                        long age = startTime - unsafe.getLong(entry + TIME_OFFSET);
                        newHistogram[Long.numberOfLeadingZeros(age)]++;

                        if (age >= expirationAge) {
                            unsafe.putAddress(currentPtr, nextEntry);
                            destroyEntry(entry);
                            expired++;
                        } else {
                            currentPtr = entry + NEXT_OFFSET;
                        }
                    }
                }
            } finally {
                lock.unlockWrite();
            }
        }

        ageHistogram = newHistogram;
        count.addAndGet(-expired);
        expirations.addAndGet(expired);
        return expired;
    }

    public void clear() {
        int cleared = 0;

        for (int i = 0; i < CONCURRENCY_LEVEL; i++) {
            RWLock lock = locks[i].lockWrite();
            try {
                for (int j = i; j < capacity; j += CONCURRENCY_LEVEL) {
                    long currentPtr = mapBase + (long) j * 8;
                    long nextEntry;
                    for (long entry = unsafe.getAddress(currentPtr); entry != 0; entry = nextEntry) {
                        nextEntry = unsafe.getAddress(entry + NEXT_OFFSET);
                        destroyEntry(entry);
                        cleared++;
                    }
                    unsafe.putAddress(currentPtr, 0);
                }
            } finally {
                lock.unlockWrite();
            }
        }

        count.addAndGet(-cleared);
    }

    public void iterate(Visitor<K, V> visitor) {
        iterate(visitor, 0, 1);
    }

    public void iterate(final Visitor<K, V> visitor, final int workers) {
        AsyncExecutor.fork(workers, new ParallelTask() {
            @Override
            public void execute(int taskNum, int taskCount) {
                iterate(visitor, taskNum, taskCount);
            }
        });
    }

    private void iterate(Visitor<K, V> visitor, int taskNum, int taskCount) {
        for (int i = taskNum; i < capacity; i += taskCount) {
            long currentPtr = mapBase + (long) i * 8;
            RWLock lock = locks[i & (CONCURRENCY_LEVEL - 1)].lockRead();
            try {
                for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
                    visitor.visit(new Record<K, V>(this, lock, entry));
                }
            } finally {
                lock.unlockRead();
            }
        }
    }

    public void iterate(WritableVisitor<K, V> visitor) {
        iterate(visitor, 0, 1);
    }

    public void iterate(final WritableVisitor<K, V> visitor, final int workers) {
        AsyncExecutor.fork(workers, new ParallelTask() {
            @Override
            public void execute(int taskNum, int taskCount) {
                iterate(visitor, taskNum, taskCount);
            }
        });
    }

    private void iterate(WritableVisitor<K, V> visitor, int taskNum, int taskCount) {
        for (int i = taskNum; i < capacity; i += taskCount) {
            long currentPtr = mapBase + (long) i * 8;
            RWLock lock = locks[i & (CONCURRENCY_LEVEL - 1)].lockWrite();
            try {
                for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; ) {
                    WritableRecord<K, V> record = new WritableRecord<K, V>(this, lock, entry, keyAt(entry), currentPtr);
                    visitor.visit(record);
                    if (record.entry != 0) {
                        currentPtr = record.entry + NEXT_OFFSET;
                    }
                }
            } finally {
                lock.unlockWrite();
            }
        }
    }

    protected long bucketFor(long hashCode) {
        return mapBase + (hashCode & Long.MAX_VALUE) % capacity * 8;
    }

    protected RWLock lockFor(long hashCode) {
        return locks[(int) hashCode & (CONCURRENCY_LEVEL - 1)];
    }

    protected void setTimeAt(long entry) {
        unsafe.putLong(entry + TIME_OFFSET, System.currentTimeMillis());
    }

    protected boolean isExpired(long entry) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - unsafe.getLong(entry + TIME_OFFSET) > timeToLive) {
            return true;
        }
        unsafe.putLong(entry + TIME_OFFSET, currentTime);
        return false;
    }

    protected K keyAt(long entry) {
        return null;  // override it in child classes if needed
    }

    protected abstract long hashCode(K key);
    protected abstract boolean equalsAt(long entry, K key);
    protected abstract V valueAt(long entry);
    protected abstract void setValueAt(long entry, V value);
    protected abstract long allocateEntry(K key, long hashCode, int size);
    protected abstract void destroyEntry(long entry);
    protected abstract int sizeOf(long entry);
    protected abstract int sizeOf(V value);

    public static class Record<K, V> {
        final OffheapMap<K, V> map;
        final RWLock lock;
        long entry;

        Record(OffheapMap<K, V> map, RWLock lock, long entry) {
            this.map = map;
            this.lock = lock;
            this.entry = entry;
        }

        public long entry() {
            return entry;
        }

        public long hash() {
            return unsafe.getLong(entry + HASH_OFFSET);
        }

        public boolean isNull() {
            return entry == 0;
        }

        public K key() {
            return map.keyAt(entry);
        }

        public V value() {
            return map.valueAt(entry);
        }

        public int size() {
            return map.sizeOf(entry);
        }

        public void release() {
            lock.unlockRead();
        }
    }

    public static class WritableRecord<K, V> extends Record<K, V> {
        K key;
        long currentPtr;

        WritableRecord(OffheapMap<K, V> map, RWLock lock, long entry, K key, long currentPtr) {
            super(map, lock, entry);
            this.key = key;
            this.currentPtr = currentPtr;
        }

        public void setValue(V value) {
            int newSize = map.sizeOf(value);

            if (entry != 0) {
                int oldSize = map.sizeOf(entry);
                if (newSize <= oldSize) {
                    map.setTimeAt(entry);
                    map.setValueAt(entry, value);
                    return;
                }

                unsafe.putAddress(currentPtr, unsafe.getAddress(entry + NEXT_OFFSET));
                map.destroyEntry(entry);
                map.count.decrementAndGet();
            }

            long hash = map.hashCode(key);
            entry = map.allocateEntry(key, hash, newSize);
            unsafe.putLong(entry + HASH_OFFSET, hash);
            unsafe.putAddress(entry + NEXT_OFFSET, unsafe.getAddress(currentPtr));
            map.setTimeAt(entry);
            map.setValueAt(entry, value);

            unsafe.putAddress(currentPtr, entry);
            map.count.incrementAndGet();
        }

        public void remove() {
            unsafe.putAddress(currentPtr, unsafe.getAddress(entry + NEXT_OFFSET));
            map.destroyEntry(entry);
            map.count.decrementAndGet();
            entry = 0;
        }

        @Override
        public K key() {
            return key;
        }

        @Override
        public void release() {
            lock.unlockWrite();
        }
    }

    public static interface Visitor<K, V> {
        void visit(Record<K, V> record);
    }

    public static interface WritableVisitor<K, V> {
        void visit(WritableRecord<K, V> record);
    }

    public class BasicCleanup extends Thread {

        public BasicCleanup(String name) {
            super(name);
            cleanupThread = this;
        }

        @Override
        public void run() {
            while (!isInterrupted()) {
                try {
                    sleep(getCleanupInterval());

                    long startTime = System.currentTimeMillis();
                    int expired = cleanup();
                    long elapsed = System.currentTimeMillis() - startTime;

                    log.info(getName() + " cleaned " + expired + " entries in " + elapsed + " ms");
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable e) {
                    log.error("Exception in " + getName(), e);
                }
            }
        }

        protected int cleanup() {
            return removeExpired(timeToLive);
        }
    }

    public class HistogramCleanup extends BasicCleanup {

        public HistogramCleanup(String name) {
            super(name);
        }

        @Override
        protected int cleanup() {
            int entriesToClean = entriesToClean();
            if (entriesToClean <= 0) {
                return 0;
            }

            int entriesExpected = 0;
            for (int age = 1; age < ageHistogram.length; age++) {
                entriesExpected += ageHistogram[age];
                if (entriesExpected >= entriesToClean) {
                    long expirationAge = Long.MIN_VALUE >>> age;
                    log.info(getName() + " needs to clean " + entriesToClean + " entries. Expected count = "
                            + entriesExpected + ", age = " + expirationAge + " (" + age + ")");
                    return removeExpired(expirationAge);
                }
            }

            return super.cleanup();
        }
    }

    public class SamplingCleanup extends BasicCleanup {
        private final Random random = new Random();

        public SamplingCleanup(String name) {
            super(name);
        }

        @Override
        protected int cleanup() {
            int entriesToClean = entriesToClean();
            if (entriesToClean <= 0) {
                return 0;
            }

            long[] timestamps = new long[1000];
            int samples = collectSamples(timestamps);
            if (samples == 0) {
                return 0;
            }

            int count = getCount();
            int k = entriesToClean < count ? (int) ((long) samples * entriesToClean / count) : 0;
            long expirationAge = System.currentTimeMillis() - QuickSelect.select(timestamps, k, 0, samples - 1);

            log.info(getName() + " needs to clean " + entriesToClean + " entries. Samples collected = "
                    + samples + ", age = " + expirationAge);
            return removeExpired(expirationAge);
        }

        private int collectSamples(long[] timestamps) {
            int samples = 0;
            int startBucket = random.nextInt(CONCURRENCY_LEVEL);
            int bucket = startBucket;

            do {
                RWLock lock = locks[bucket].lockRead();
                try {
                    for (int i = bucket; i < capacity; i += CONCURRENCY_LEVEL) {
                        long currentPtr = mapBase + (long) i * 8;
                        for (long entry; (entry = unsafe.getAddress(currentPtr)) != 0; currentPtr = entry + NEXT_OFFSET) {
                            if (samples >= timestamps.length) return samples;
                            timestamps[samples++] = unsafe.getLong(entry + TIME_OFFSET);
                        }
                    }
                } finally {
                    lock.unlockRead();
                }
            } while (samples < 50 && (++bucket & (CONCURRENCY_LEVEL - 1)) != startBucket);

            return samples;
        }
    }
}
