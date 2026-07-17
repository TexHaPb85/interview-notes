# Redis vs Redux

The names look almost the same, but they are **completely unrelated** technologies. This
is a classic "gotcha" — interviewers sometimes ask just to see if you mix them up.

- **Redis** = a fast **data store / cache** that runs on the **server** (backend).
- **Redux** = a **state management** library that runs in the **browser** (frontend, React).

A real application often uses **both at the same time** — they do not compete.

---

## Quick comparison

|                | **Redis**                                  | **Redux**                                   |
|----------------|--------------------------------------------|---------------------------------------------|
| What it is     | In-memory data store / cache (a server)    | State management library (JavaScript)       |
| Runs where     | **Backend** (own server/process)           | **Frontend** (inside the React app)         |
| Main job       | Store & serve data very fast               | Hold the UI's shared state in one place     |
| Data lives in  | RAM on the Redis server (can persist)      | Browser memory (gone on page refresh)       |
| Used from      | Any language (Java, Node, Python, …)       | JavaScript / TypeScript                     |
| Survives reload| Yes (it's a separate server)               | No (unless you save it yourself)            |

---

## Redis — fast storage on the server

**Redis** (REmote DIctionary Server) keeps data **in memory**, so reads/writes are
extremely fast (sub-millisecond). It stores **key → value** pairs, where values can be
strings, lists, sets, hashes, etc.

**Common uses:**

- **Caching** — store the result of a slow DB query or API call so the next request is
  instant.
- **Sessions** — keep HTTP session data so many app servers can share it.
- **Rate limiting / counters** — count requests per user quickly.
- **Queues / pub-sub** — simple message passing between services.

### Example: Redis with Java / Spring

The easiest way is Spring's caching with a Redis backend. The first call hits the database;
the result is stored in Redis, so the next calls are served from cache.

```java
@Service
public class UserService {

    @Cacheable("users")               // look in Redis first
    public User getUser(Long id) {
        // runs only on a cache miss
        return userRepository.findById(id).orElseThrow();
    }

    @CacheEvict(value = "users", key = "#user.id")  // remove stale cache on update
    public void updateUser(User user) {
        userRepository.save(user);
    }
}
```

You can also use Redis directly with `RedisTemplate`:

```java
@Autowired
private RedisTemplate<String, String> redis;

redis.opsForValue().set("user:5:name", "Anna");   // write
String name = redis.opsForValue().get("user:5:name"); // read
```

> Dependency: `spring-boot-starter-data-redis`. Add `@EnableCaching` to use `@Cacheable`.

---

## Redux — state box in the React app

**Redux** keeps the **shared state** of your UI (logged-in user, cart, theme, etc.) in one
central **store**, so any component can read it without "props drilling". State changes go
through one predictable flow:

```
dispatch(action)  ->  reducer  ->  new state  ->  components re-render
```

- **Store** — the single object that holds all state.
- **Action** — a plain message describing "what happened" (`increment`, `addToCart`).
- **Reducer** — a function that takes the old state + action and returns the new state.
- **Selector / dispatch** — components read state with `useSelector`, send actions with
  `useDispatch`.

### Example: Redux with React (Redux Toolkit)

```tsx
// counterSlice.ts
import { createSlice } from "@reduxjs/toolkit";

const counterSlice = createSlice({
  name: "counter",
  initialState: { value: 0 },
  reducers: {
    increment: (state) => { state.value++; },
    addBy: (state, action) => { state.value += action.payload; },
  },
});

export const { increment, addBy } = counterSlice.actions;
export default counterSlice.reducer;
```

```tsx
// Counter.tsx
import { useSelector, useDispatch } from "react-redux";
import { increment } from "./counterSlice";

const Counter = () => {
  const value = useSelector((state: any) => state.counter.value);
  const dispatch = useDispatch();

  return <button onClick={() => dispatch(increment())}>Count: {value}</button>;
};
```

> Modern React often replaces Redux with `useContext` + `useReducer` for small apps, or
> tools like Zustand / React Query for server data. Redux is still common in large apps.

---

## How to remember (interview answer)

- **Redis = server-side fast storage/cache.** Used from Java/Spring, Node, etc. Survives
  page reloads because it's a separate server.
- **Redux = client-side state container for the React UI.** Lives in the browser; state is
  lost on refresh unless you save it.
- They solve **different problems on different sides** of the app and are often used
  **together**: Redux holds UI state in the browser, Redis caches data on the backend.
