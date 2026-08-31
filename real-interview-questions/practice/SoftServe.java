/**
 * Filename: Code.java
 * Environment: https://codeinterview.io/languages/java
 * https://codeinterview.io/JJZELZTMOT
 *
 * NOTE: Do not declare any packages.
 */

/*

How LRU Works
Insertion: When a new item is added to the cache, it is stored at the most recently used position.
Access: When an item is accessed, it is moved to the most recently used position.
Eviction: When the cache reaches its maximum capacity, the item that has not been used for the longest time is removed.

*/

class LRUCache {
    private int capacity;
    private List<Integer> cache;
    private Map<Integer, Integer> values;

    public LRUCache(int capacity) {
        this.cache = new LinkedList<>();
        this.values = new HashMap<>();
        this.capacity = capacity;
    }

    public int get(int key) {
        // TODO: Implement LRU cache get operation
        if(!values.containsKey(key)) {
            return -1;
        }
        cache.removeIf(el -> el==key);
        cache.addFirst(key);
        return values.get(key);
    }

    public void put(int key, int value) {
        // TODO: Implement LRU cache put operation

        if(values.containsKey(key)) {//O(1)
            cache.remove(key);//O(n)
        }
        cache.addFirst(key);//O(1)
        values.put(key, value);//O(1)
        if(cache.size() > capacity) {
            Integer removed = cache.removeLast();//O(1)
            values.remove(removed);//O(1)
        }
    }
}

class Main {
    public static void main(String[] args) {
        LRUCache cache = new LRUCache(2);  // Capacity of 2

        cache.put(1, 1);   // Cache is {1=1}
        cache.put(2, 2);   // Cache is {1=1, 2=2}
        System.out.println(cache.get(1));  // returns 1
        cache.put(3, 3);   // Evicts key 2, cache is {1=1, 3=3}
        System.out.println(cache.get(2));  // returns -1 (not found)
    }
}


interface EvictionPolicy {
    void trackUsage();
    void evict();
}

class Cache {

    // should support different eviction policies ( FIFO, LRU, etc )

    public Cache(EvictionPolicy evictionPolicy) {}

    public int get(int key) {
        EvictionPolicy.trackUsage(key);
        throw new UnsupportedOperationException();
    }
    public void put(int key, int value) {
        EvictionPolicy.evict();
        throw new UnsupportedOperationException();
    }
}

// Object evictionPolicy = ... ( can be FIFO, LRU, etc )
// usageExample - Cache cache = new Cache(evictionPolicy);