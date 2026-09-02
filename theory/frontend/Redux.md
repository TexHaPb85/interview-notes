# React

## Quick links

- [What is React?](#what-is-react)
- [JSX and components](#jsx-and-components)
- [Props vs state](#props-vs-state)
- [Hooks](#hooks)
- [Virtual DOM and rendering](#virtual-dom-and-rendering)
- [State management and Redux](#state-management-and-redux)
- [Redux with React (Redux Toolkit)](#redux-with-react-redux-toolkit)
- [Alternatives to Redux](#alternatives-to-redux)
- [Most popular interview questions](#most-popular-interview-questions)

---

## What is React?

**React** is a JavaScript **library** for building user interfaces (not a full
framework — routing, HTTP calls, etc. come from other libraries). Built and maintained
by Meta.

- **Component-based** — a UI is built from small, reusable pieces (components), each
  responsible for one part of the screen.
- **Declarative** — you describe *what* the UI should look like for a given state, and
  React figures out *how* to update the real page to match. You don't manually write
  "find this element, change its text".

## JSX and components

**JSX** is a syntax extension that lets you write HTML-like markup inside JavaScript. It
compiles down to plain `React.createElement(...)` calls — it's not magic, just sugar.

```tsx
function Greeting({ name }: { name: string }) {
  return <h1>Hello, {name}!</h1>;
}
```

- **Functional components** (like above) are the modern standard — a plain function that
  returns JSX.
- **Class components** are the older style, using `class X extends React.Component` with
  a `render()` method. Still valid, but rarely written in new code today — hooks (below)
  replaced most of the reasons to use them.

## Props vs state

| | **Props** | **State** |
|---|---|---|
| Where it comes from | Passed in by the **parent** component | Owned and managed by the component **itself** |
| Can the component change it? | No — read-only | Yes |
| Triggers re-render on change? | Yes, when the parent passes new props | Yes |

```tsx
function Counter({ start }: { start: number }) {   // "start" is a prop — read-only
  const [count, setCount] = useState(start);        // "count" is state — this component owns it

  return <button onClick={() => setCount(count + 1)}>Count: {count}</button>;
}
```

## Hooks

Hooks let a functional component use state, side effects, and other React features
without writing a class.

- **`useState`** — local state for a component.

  ```tsx
  const [value, setValue] = useState(0);
  ```

- **`useEffect`** — runs a "side effect" (fetching data, subscribing to something) after
  render. The dependency array controls when it re-runs; an optional cleanup function
  runs before the next effect (or on unmount).

  ```tsx
  useEffect(() => {
    const timer = setInterval(() => console.log("tick"), 1000);
    return () => clearInterval(timer); // cleanup — runs on unmount / before next effect
  }, []); // empty array = run once, after the first render
  ```

- **`useContext`** — read a value from a `Context`, without passing it down as a prop
  through every layer in between ("prop drilling").

  ```tsx
  const theme = useContext(ThemeContext);
  ```

- **`useRef`** — holds a mutable value that does **not** trigger a re-render when
  changed, or gives direct access to a DOM element.

  ```tsx
  const inputRef = useRef<HTMLInputElement>(null);
  <input ref={inputRef} />;
  ```

- **Custom hooks** — a plain function (name starting with `use`) that wraps reusable
  stateful logic, built from the hooks above.

**Rules of hooks:** only call hooks at the top level of a component (never inside a
loop, condition, or nested function), and only from React function components or other
custom hooks. React relies on hooks being called in the same order every render to
match up state correctly.

## Virtual DOM and rendering

React keeps a lightweight, in-memory copy of the UI tree — the **virtual DOM**. When
state changes:

1. React re-runs the component function(s) to build a new virtual DOM tree.
2. It **diffs** the new tree against the previous one (reconciliation).
3. It applies only the **minimal set of changes** needed to the real DOM, instead of
   re-rendering the whole page.

This is why list items need a stable, unique **`key`** prop — React uses keys to match
up items between the old and new tree correctly (which one moved vs. which one is new),
instead of guessing by position.

```tsx
{users.map(user => <li key={user.id}>{user.name}</li>)}
```

## State management and Redux

For small pieces of state, `useState` inside a component (or `useContext` for a value a
few components need) is enough. As an app grows, two problems show up:

- **Prop drilling** — passing a value down through five layers of components that don't
  themselves need it, just to reach the one that does.
- **Shared state changing often** — `useContext` re-renders every consumer on any
  change, which gets expensive for state that updates frequently (e.g. a live cart total).

**Redux** solves this by keeping all shared state in **one central store**, with a
single, predictable way to change it:

```text
dispatch(action)  ->  reducer  ->  new state  ->  components re-render
```

- **Store** — the one object holding all the app's shared state.
- **Action** — a plain object describing "what happened" (e.g. `{ type: "increment" }`).
- **Reducer** — a pure function: takes the old state + an action, returns new state. Never
  mutates the old state directly.
- **Dispatch / selector** — components send actions with `dispatch(action)`, and read
  state with a selector (`useSelector` in React).

## Redux with React (Redux Toolkit)

Redux Toolkit is the modern, recommended way to write Redux — much less boilerplate than
classic Redux.

```tsx
// counterSlice.ts
import { createSlice, type PayloadAction } from "@reduxjs/toolkit";

const counterSlice = createSlice({
  name: "counter",
  initialState: { value: 0 },
  reducers: {
    increment: (state) => { state.value++; },              // looks mutating, but
    addBy: (state, action: PayloadAction<number>) => {      // Redux Toolkit uses Immer
      state.value += action.payload;                        // under the hood to keep it safe
    },
  },
});

export const { increment, addBy } = counterSlice.actions;
export default counterSlice.reducer;
```

```tsx
// store.ts
import { configureStore } from "@reduxjs/toolkit";
import counterReducer from "./counterSlice";

export const store = configureStore({
  reducer: { counter: counterReducer },
});
export type RootState = ReturnType<typeof store.getState>;
```

```tsx
// Counter.tsx
import { useSelector, useDispatch } from "react-redux";
import { increment } from "./counterSlice";
import type { RootState } from "./store";

const Counter = () => {
  const value = useSelector((state: RootState) => state.counter.value);
  const dispatch = useDispatch();

  return <button onClick={() => dispatch(increment())}>Count: {value}</button>;
};
```

**Handling async logic (e.g. an API call):** a reducer must stay a pure, synchronous
function, so Redux needs a separate tool for side effects. Redux Toolkit's built-in
answer is `createAsyncThunk`:

```tsx
export const fetchUser = createAsyncThunk("user/fetch", async (id: number) => {
  const res = await fetch(`/api/users/${id}`);
  return res.json();
});
```

This dispatches `pending` / `fulfilled` / `rejected` actions automatically as the
request runs, which a slice's `extraReducers` can handle to update loading/error/data
state. (Older codebases may instead use `redux-thunk` or `redux-saga` directly — same
idea, different tool.)

## Alternatives to Redux

Redux isn't the only option, and it's often more than a small app needs:

- **`useContext` + `useReducer`** — a built-in, no-dependency way to get a Redux-like
  local pattern for simpler, less frequently-changing global state.
- **Zustand** — a small, minimal-boilerplate state library, popular as a lighter Redux
  alternative.
- **TanStack Query (React Query)** — not a general state library; specifically for
  *server* data (fetching, caching, refetching) — often used **alongside** Redux/Zustand,
  which then only holds true client-side UI state.
- **Recoil / Jotai** — atom-based state libraries, a different mental model (many small
  independent pieces of state instead of one big store).

Redux is still common in large, established apps where predictability, one clear data
flow, and good devtools (time-travel debugging) matter.

## Most popular interview questions

**What is the virtual DOM, and why does it help performance?**
It's an in-memory copy of the UI tree that React diffs against the previous version, so
only the actually-changed parts of the real DOM get updated — direct DOM updates are
comparatively slow, so minimizing them helps performance.

**What's the difference between props and state?**
Props are passed in by the parent and are read-only from the component's own point of
view. State is owned and changed by the component itself. Both trigger a re-render when
they change.

**What do the dependency array and cleanup function in `useEffect` do?**
The dependency array controls when the effect re-runs — `[]` means only after the first
render, listing values means it re-runs whenever any of them change. The returned
cleanup function runs before the next time the effect fires, and on unmount — used to
cancel subscriptions, timers, or pending requests.

**What are the rules of hooks, and why do they matter?**
Call hooks only at a component's top level (not inside loops/conditions), and only from
React functions. React matches hook calls to their state by the **order** they're called
in on every render — calling them conditionally would break that matching.

**Why do list items need a stable `key` prop?**
React uses keys to match items between renders — which one moved, which one is new,
which one was removed. Using array index as a key can cause wrong matches (and buggy
UI/state) when items are reordered or removed.

**What problem does Redux solve that `useContext` alone doesn't handle well?**
`useContext` re-renders every component reading that context on any change, which gets
expensive for state that updates often. Redux centralizes state with a predictable
update flow and lets components subscribe only to the specific state slice they need via
selectors.

**Walk through the Redux data flow.**
A component calls `dispatch(action)`. The store passes the current state and that action
to the reducer, a pure function that returns the new state — never mutating the old one.
The store saves the new state and notifies subscribed components, which re-render.

**How do you handle asynchronous logic (like an API call) in Redux?**
Reducers have to stay synchronous and pure, so async logic lives outside them — in Redux
Toolkit, `createAsyncThunk` dispatches pending/fulfilled/rejected actions automatically
around a promise; a slice's `extraReducers` updates loading/data/error state in response.

**What's the difference between controlled and uncontrolled components?**
A controlled input's value is driven by React state (`value={state}` +
`onChange`) — React is the single source of truth. An uncontrolled input keeps its own
internal DOM state, and you read it on demand (e.g. via a `ref`) instead of on every
keystroke.

**When would you NOT reach for Redux?**
For a small app, or state that's mostly local to one part of the tree — `useState` plus
maybe `useContext` is simpler and has no extra dependency. Redux earns its complexity
when a lot of unrelated components need to read/update the same state predictably.
