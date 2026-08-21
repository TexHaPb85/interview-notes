# React JS / TS

## Hooks

| # | Hook          | What it gives you                                                         |
|---|---------------|-----------------------------------------------------------------------------|
| 1 | `useState`    | local state variable in a React component that trigers rerender on update |
| 2 | `useEffect`   | run a function depending on state variables passed in the deps array      |
| 3 | `useContext`  | global context values (no props drilling)                                 |
| 4 | `useReducer`  | complex state logic (alternative to Redux for local state)                |
| 5 | `useMemo`     | save expensive calculations                                               |
| 6 | `useCallback` | memoize functions to avoid re-renders                                     |
| 7 | `useRef`      | store mutable values or access DOM elements                               |

---

## 1. useState — local state variable

**Problem:** Standard variables do not trigger a UI update when they change. If you update a `let count = 0` variable,
React doesn't know it needs to re-render the screen.

**Solution:** `useState` gives you a variable that, when updated via its setter function,
tells React to re-render the component with the new value.

Hook `useState` stores state inside a single React component.

```tsx
import React, { useState } from "react";

const Counter = () => {
  const [count, setCount] = useState(0); // [value, setter]

  return (
          <div>
            <p>Count: {count}</p>
            {/* Calling setCount triggers a re-render */}
            <button onClick={() => setCount(count + 1)}>Increment</button>
          </div>
  );
};
```

## 2. useEffect — run side effects
**Problem:** You need to do something outside of React's normal rendering flow,
like fetching data from an API, subscribing to an event, or manually changing the DOM.
If you put this logic directly in the component body, it will run on every single render,
causing infinite loops or performance nightmares.

**Solution:** useEffect runs your code after the component renders, and the dependency array [] controls exactly when it runs again.

```tsx
import React, { useState, useEffect } from "react";

interface DataTableProps<T> {
  columns: Column<T>[];
  fetchData: (page?: number, size?: number, cancelToken?: CancelToken) => Promise<PageResponse<T>>;
  onEdit?: (item: T) => Promise<void>;
  pageSize?: number;
}

export function DataTable<T extends { id: number }>({
                                                      columns,
                                                      fetchData,
                                                      onEdit,
                                                      pageSize = 10,
                                                    }: DataTableProps<T>) {
  const [data, setData] = useState<T[]>([]);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [editedItem, setEditedItem] = useState<Partial<T>>({});

  useEffect(() => {
    fetchData(page, pageSize).then(res => {
      setData(res.content);
      setTotal(res.totalElements);
    });
  }, [page, pageSize]);
```

### Dependency array — how it changes when the function runs

| Array passed | When the effect function runs |
|---|---|
| none — `useEffect(() => {...})` | after **every** render |
| `[]` — empty array | only **once**, right after the first render (like `componentDidMount`) |
| `[a, b]` | after the first render, then again only when `a` or `b` changes |

```tsx
useEffect(() => {
  console.log("runs after every render");
});

useEffect(() => {
  console.log("runs one time only, right after mount");
}, []);

useEffect(() => {
  console.log("runs again only when page or pageSize changes");
}, [page, pageSize]);
```

**Cleanup function:** if the effect returns a function, React calls that function before running the effect again, and also when the component unmounts.

```tsx
useEffect(() => {
  const id = setInterval(() => console.log("tick"), 1000);
  return () => clearInterval(id); // cleanup - stops the old interval first
}, []);
```

---

## useContext — share data without props drilling

**Problem:** sometimes you need to pass the same data (theme, language, user info) down
many levels of components. Passing it via props becomes ugly.

**Solution:** `useContext` lets you access global values without manually passing props.

```tsx
import React, { createContext, useContext } from "react";

const ThemeContext = createContext("light"); // default value = "light"

const Button = () => {
  const theme = useContext(ThemeContext); // get value from context
  return <button style={{ background: theme === "dark" ? "black" : "white" }}>Click</button>;
};

const App = () => (
  <ThemeContext.Provider value="dark">
    <Button />  {/* gets "dark" automatically */}
  </ThemeContext.Provider>
);

export default App;
```

**When useful?**

- App-wide settings (theme, language)
- Authentication (current user)
- Sharing API clients or config

---

### Re-render rule: it does not matter HOW you use the value

When the `Provider`'s `value` changes, **every** component that calls `useContext(ThisContext)` re-renders. This is true even if you don't put the value into `useState` — even if you only pass it into a function or just read one field from it. React re-runs the whole component function again, no matter what you do inside that function.

```tsx
const CountContext = createContext(0);

const LoggerOnly = ({ children }: { children: React.ReactNode }) => {
  const count = useContext(CountContext);

  logSomewhere(count); // count is only passed to a function, never saved to state

  console.log("LoggerOnly rendered"); // <- still prints on every Provider update
  return <div>{children}</div>;
};
```

Every time `CountContext` value changes, `LoggerOnly` renders again and prints the log line — even though `count` never goes through `useState`. The re-render is triggered by React itself because the component reads the context, not by how the value is used inside.

---

### Complex example: key-value object in context (with update function)

Real context often holds an object (key-value map), not just one string. Example: a price list, plus a function to change one price and keep the rest unchanged.

```tsx
// 1. types
type PriceMap = Record<string, number>; // { "apple": 10, "banana": 5 }

interface PriceContextType {
  prices: PriceMap;
  setPrice: (key: string, value: number) => void; // updater goes through context too
}

const PriceContext = createContext<PriceContextType | null>(null);
```

**Provider (with comments):**

```tsx
const PriceProvider = ({ children }: { children: React.ReactNode }) => {
  // 1. the real key-value data lives in useState
  const [prices, setPrices] = useState<PriceMap>({
    apple: 10,
    banana: 5,
  });

  // 2. function to update ONE key, keeping all other keys as they are
  const setPrice = (key: string, value: number) => {
    setPrices(prev => ({
      ...prev,       // copy all old key-value pairs
      [key]: value,  // overwrite (or add) just this one key
    }));
  };

  // 3. object given to the Provider - data + the function that changes it
  const value: PriceContextType = { prices, setPrice };

  return (
    <PriceContext.Provider value={value}>
      {children}
    </PriceContext.Provider>
  );
};
```

**Put a new value into the existing context (called from any child):**

```tsx
const ProductRow = ({ id }: { id: string }) => {
  const ctx = useContext(PriceContext);
  if (!ctx) throw new Error("ProductRow must be inside PriceProvider");

  const { prices, setPrice } = ctx;

  return (
    <div>
      <span>{id}: ${prices[id] ?? 0}</span>
      {/* calling setPrice updates context - every reader re-renders */}
      <button onClick={() => setPrice(id, (prices[id] ?? 0) + 1)}>+1</button>
    </div>
  );
};
```

Calling `setPrice` inside `ProductRow` updates the state inside `PriceProvider`. React builds a new `prices` object and a new `value` object, so the `Provider` sends a new reference down — every component that called `useContext(PriceContext)` re-renders, same rule as above.

> Note: `value = { prices, setPrice }` is a new object on every Provider render. In a big app this can cause extra re-renders. Fix: wrap it with `useMemo(() => ({ prices, setPrice }), [prices])`. More on this in the `useMemo` section below.

---

> **Summary:** the `default value` in `createContext(...)` is used **only** when a component reads the context with no `Provider` above it. When the `Provider`'s `value` changes, **all** consumers re-render — no matter what they do with the value inside.

---

## useMemo — save expensive calculations

**Problem:** React re-renders components a lot. If you run a heavy calculation on every
render, your app slows down.

**Solution:** `useMemo` remembers a value until its dependencies change.

### Case 1: without useMemo

Imagine we have a slow calculation:

```tsx
import React, { useState } from "react";

const slowFunction = (num: number) => {
  console.log("Running slow function...");
  for (let i = 0; i < 1_000_000_000; i++) {} // simulate heavy work
  return num * 2;
};

const App = () => {
  const [number, setNumber] = useState(1);
  const [text, setText] = useState("");

  // This runs on EVERY render, even if only "text" changes
  const doubled = slowFunction(number);

  return (
    <div>
      <h2>Number: {doubled}</h2>
      <button onClick={() => setNumber(number + 1)}>Increase</button>
      <input value={text} onChange={(e) => setText(e.target.value)} />
    </div>
  );
};
```

If you type in the input (`text`), React re-renders. Every render runs
`slowFunction(number)` again and blocks the UI — even though `number` didn't change.

### Case 2: with useMemo

```tsx
import React, { useState, useMemo } from "react";

const slowFunction = (num: number) => {
  console.log("Running slow function...");
  for (let i = 0; i < 1_000_000_000; i++) {}
  return num * 2;
};

const App = () => {
  const [number, setNumber] = useState(1);
  const [text, setText] = useState("");

  // Runs only when "number" changes
  const doubled = useMemo(() => slowFunction(number), [number]);

  return (
    <div>
      <h2>Number: {doubled}</h2>
      <button onClick={() => setNumber(number + 1)}>Increase</button>
      <input value={text} onChange={(e) => setText(e.target.value)} />
    </div>
  );
};
```

Now, if you type in the input, React re-renders but does **not** recalc `slowFunction`.
`slowFunction` only runs when `number` changes.

### Why is useMemo useful?

- **Performance:** skip expensive work unless inputs change.
- **Stability:** if you pass a calculated object/array to children, memoizing keeps the
  same reference and prevents re-renders (works together with `React.memo` on the child).

**Example 2:**

```tsx
const newLevel = useMemo(() => {
  return levels
    .sort((a, b) => b.requiredPoints - a.requiredPoints)
    .find((l) => l.requiredPoints <= pointsState.value);
}, [levels, pointsState.value]);
```

**Example 3:**

```tsx
const filtered = useMemo(
  () => users.filter((u) => u.includes(search)),
  [users, search]
);
```

**When useful?**

- Heavy math / data processing
- Filtering or sorting large lists
- Avoid recalculating on every render

---

## useCallback

Memoize **functions** to avoid re-renders (keeps the same function reference between
renders). `useMemo` memoizes a **value**; `useCallback` memoizes a **function**.

Useful when you pass a callback to a child wrapped in `React.memo`: without
`useCallback` a new function is created every render, so the child re-renders anyway.

```tsx
const Child = React.memo(({ onClick }: { onClick: () => void }) => {
  console.log("Child render");
  return <button onClick={onClick}>Click</button>;
});

const App = () => {
  const [count, setCount] = useState(0);

  // same function reference between renders -> Child does not re-render
  const handleClick = useCallback(() => console.log("clicked"), []);

  return (
    <div>
      <p>{count}</p>
      <button onClick={() => setCount(count + 1)}>+</button>
      <Child onClick={handleClick} />
    </div>
  );
};
```

## useRef

Store a mutable value that **survives re-renders but does not trigger one** when it
changes, or get direct access to a DOM element.

```tsx
const App = () => {
  const inputRef = useRef<HTMLInputElement>(null);  // DOM access
  const renders = useRef(0);                         // mutable value, no re-render

  renders.current++; // changing .current does NOT cause a re-render

  return (
    <div>
      <input ref={inputRef} />
      <button onClick={() => inputRef.current?.focus()}>Focus</button>
      <p>Renders: {renders.current}</p>
    </div>
  );
};
```

> `useRef` vs `useState`: changing `state` re-renders the component; changing
> `ref.current` does not. Use a ref for values you want to keep but not show directly.

---

## Common JavaScript / TypeScript interview questions

### Promises & async/await

**Q: What is a Promise?**
A Promise is an object that represents a value that is **not ready yet** — the result of
an async operation (HTTP call, timer, file read). Instead of blocking, your code keeps
running and the Promise calls you back when it is done.

A Promise has **three states**:

| State       | Meaning                                  |
|-------------|-------------------------------------------|
| `pending`   | still running, no result yet             |
| `fulfilled` | finished OK, has a value (`.then`)       |
| `rejected`  | failed, has an error (`.catch`)          |

Once it leaves `pending` it is **settled** and never changes again.

```ts
const p = new Promise<string>((resolve, reject) => {
  setTimeout(() => resolve("done"), 1000); // or reject(new Error("fail"))
});

p.then(value => console.log(value))   // "done" after 1s
 .catch(err => console.error(err))
 .finally(() => console.log("always runs"));
```

---

**Q: What is `async` / `await`?**
`async`/`await` is **syntax sugar over Promises** — same thing, easier to read.

- An `async` function **always returns a Promise**, even if you `return 5` (it becomes
  `Promise<number>`).
- `await` **pauses** inside the async function until the Promise settles, then gives you
  the unwrapped value. It does **not** block the whole page — other code keeps running.

```ts
async function getUser(): Promise<string> {
  const res = await fetch("/api/user"); // wait for the response
  const data = await res.json();         // wait for the body
  return data.name;                      // wrapped into Promise<string>
}
```

---

**Q: How do you handle errors with `async`/`await`?**
Wrap it in `try/catch` (the `await` version of `.catch`).

```ts
async function load() {
  try {
    const data = await getUser();
    console.log(data);
  } catch (err) {
    console.error("failed:", err); // a rejected Promise lands here
  } finally {
    console.log("cleanup");
  }
}
```

---

**Q: Sequential vs parallel `await` (very common trap).**
`await` in a loop runs requests **one after another** — slow. If the calls do not depend
on each other, start them together and `await` them with `Promise.all`.

```ts
// SLOW: 3 requests one by one (sum of all times)
for (const id of ids) {
  await fetchUser(id);
}

// FAST: all 3 at the same time (longest time only)
await Promise.all(ids.map(id => fetchUser(id)));
```

---

**Q: `Promise.all` vs `allSettled` vs `race` vs `any`?**

| Method                 | Resolves when…                       | Rejects when…                  | Use for                              |
|------------------------|---------------------------------------|----------------------------------|----------------------------------------|
| `Promise.all`          | **all** succeed                      | **any one** fails (fail-fast)  | need every result, all-or-nothing    |
| `Promise.allSettled`   | **all** finish (ok or fail)          | never                          | want every outcome, ignore failures  |
| `Promise.race`         | the **first** one settles (ok/fail)  | first settles with a rejection | timeouts, fastest-wins               |
| `Promise.any`          | the **first** one **succeeds**       | only if **all** fail           | first successful of several sources  |

```ts
const results = await Promise.allSettled([fetchA(), fetchB()]);
// [{ status: "fulfilled", value: ... }, { status: "rejected", reason: ... }]
```

---

**Q: What is the event loop? Microtask vs macrotask (classic output question).**
JavaScript is **single-threaded**. Async work is queued and run by the **event loop**
when the call stack is empty. There are two queues:

- **Microtasks** — `Promise.then`, `await` continuations. Run **first**, before the next macrotask.
- **Macrotasks** — `setTimeout`, `setInterval`, I/O. Run **after** all microtasks.

```ts
console.log("1");
setTimeout(() => console.log("2"), 0);   // macrotask
Promise.resolve().then(() => console.log("3")); // microtask
console.log("4");

// Output: 1, 4, 3, 2
// sync code (1,4) -> microtasks (3) -> macrotasks (2)
```

---

### General JavaScript

**Q: `==` vs `===`?**
`===` is strict — compares value **and** type, no conversion. `==` converts types first
(`0 == ""` is `true`, `0 === ""` is `false`). **Always prefer `===`.**

**Q: `var` vs `let` vs `const`?**

| Keyword | Scope    | Reassign? | Hoisting                                  |
|---------|----------|-----------|---------------------------------------------|
| `var`   | function | yes       | hoisted, initialized `undefined`          |
| `let`   | block    | yes       | hoisted but in TDZ (error if used early)  |
| `const` | block    | no        | hoisted but in TDZ                        |

> TDZ = Temporal Dead Zone: the time between the start of the block and the line where
> `let`/`const` is declared. Using the variable there throws a `ReferenceError`.

**Q: What is a closure?**
A function that **remembers the variables of the scope where it was created**, even after
that outer function has returned.

```ts
function counter() {
  let count = 0;
  return () => ++count; // keeps access to `count`
}
const next = counter();
next(); // 1
next(); // 2
```

---

### TypeScript

**Q: `interface` vs `type`?**
Both describe the shape of an object. `interface` can be **re-opened / extended** (declaration
merging) and is preferred for object/class contracts; `type` is more flexible — it can also
describe **unions**, **primitives**, and **tuples**.

```ts
interface User { id: number; name: string; }
type ID = string | number;        // union — only `type` can do this
type Point = [number, number];    // tuple
```

**Q: `any` vs `unknown` vs `never`?**

| Type      | Meaning                                                                          |
|-----------|------------------------------------------------------------------------------------|
| `any`     | turns **off** type checking — avoid it                                            |
| `unknown` | "could be anything", but you **must narrow** it before use (type-safe `any`)     |
| `never`   | a value that **never happens** (function that always throws / infinite loop)     |

**Q: What are generics?**
Types that take a **parameter**, so one function/type works for many types while staying
type-safe.

```ts
function first<T>(arr: T[]): T {
  return arr[0];
}
first([1, 2, 3]);       // T = number
first(["a", "b"]);      // T = string
```

> Bonus operators: optional chaining `user?.address?.city` (stop if `null`/`undefined`)
> and nullish coalescing `value ?? "default"` (use default only for `null`/`undefined`,
> not for `0` or `""`).

---

## useMemo vs useCallback vs React.memo

| Tool          | What it memoizes                | Returns                                                    |
|---------------|-----------------------------------|---------------------------------------------------------------|
| `useMemo`     | the **result** of a calculation | the cached value                                           |
| `useCallback` | a **function** definition       | the cached function                                        |
| `React.memo`  | a **component**                 | a wrapped component that skips re-render if props are equal |

> `useCallback(fn, deps)` is the same as `useMemo(() => fn, deps)`.
> `React.memo` is a higher-order component (HOC), **not** a hook.