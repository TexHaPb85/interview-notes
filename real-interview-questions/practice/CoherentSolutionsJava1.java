/*
 * Click `Run` to execute the snippet below!
 */
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


class System
{
    private int priority;
    private String name;

    public System(int priority, String name)
    {
        this.priority = priority;
        this.name = name;
    }

    public System()
    {
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }
}

enum EventType {
    NETWORK_FAULT, SUCCESS, EXTERNAL_INTERACTION
}

class Event
{
    private int id;
    private EventType type;
    private long timestamp;

    public Event(int id, EventType type, long timestamp)
    {
        this.id = id;
        this.type = type;
        this.timestamp = timestamp;
    }

    public Event()
    {
    }

    public int getId()
    {
        return id;
    }

    public void setId(int id)
    {
        this.id = id;
    }

    public EventType getType()
    {
        return type;
    }

    public void setType(EventType type)
    {
        this.type = type;
    }

    public long getTimestamp()
    {
        return timestamp;
    }

    public void setTimestamp(long timestamp)
    {
        this.timestamp = timestamp;
    }
}

/*
    @SingleThread
 */
class EventStore
{
    private final Map<System, List<Event>> systemEvents = new HashMap<>();

    /**
     * Adds event to the cache
     *
     * @param system
     * @param event
     */
    public void add(final System system, final Event event)
    {

    }

    /**
     * @param system to search events for
     * @return all {@link EventType.NETWORK_FAULT} events for the given system
     */
    public List<Event> findFaultsForSystem(final System system)
    {
        List<Event> evetsBySys = systemEvents.get(system);
        List<Event> res = evetsBySys.stream().filter(ev -> ev.getType() == EventType.NETWORK_FAULT).toList(); //O(n)
        return res;
    }

    /**
     * @return all events regardless of the system
     */
    public List<Event> findAll()
    {
        return null;
    }
}

/*
 * To execute Java, please define "static void main" on a class
 * named Solution.
 *
 * If you need more classes, simply define them inline.
 */
class Solution {
    public static void main(String[] args) {
        final EventStore store = new EventStore();

        final System system = new System(1, "System1");
        final System system2 = new System(1, "System2");


        final Event event = new Event(1, EventType.NETWORK_FAULT, Instant.now().toEpochMilli());
        final Event event2 = new Event(2, EventType.EXTERNAL_INTERACTION, Instant.now().toEpochMilli());

        store.add(system, event);
        store.add(system2, event2);

        //4. Find all and count by event type
        List<Event> allEvents = store.findAll();
    }
}


async
immutable
removing by time
//private final Map<System, Map<EventType, List<Event>>> systemEvents = new ConcurrentHashMap<>();
