# React

## Quick links

- [State management and Redux](#state-management-and-redux)
- [Redux with React (Redux Toolkit)](#redux-with-react-redux-toolkit)
- [Alternatives to Redux](#alternatives-to-redux)
- [Most popular interview questions](#most-popular-interview-questions)

---

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
