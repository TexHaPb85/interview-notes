# Redux

## Quick links

- [State management and Redux](#state-management-and-redux)
- [Why not just Context?](#why-not-just-context)
- [Redux with React (Redux Toolkit)](#redux-with-react-redux-toolkit)
- [Alternatives to Redux](#alternatives-to-redux)
- [Most popular interview questions](#most-popular-interview-questions)

---

## State management and Redux

For small pieces of state, `useState` inside a component (or `useContext` for a value a
few components need) is enough. As an app grows, two problems show up:

- **Prop drilling** — passing a value down through five layers of components that don't
  themselves need it, just to reach the one that does.
- **Shared state changing often** — many components need to read (and sometimes write)
  the same piece of state, and it updates a lot.

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

## Why not just Context?

`useContext`, maybe combined with `useReducer`, can absolutely give you a small,
dependency-free version of the Redux pattern — one shared piece of state, updated
through actions. For plenty of apps, that's genuinely enough. The reasons teams still
reach for Redux come down to two things Context doesn't solve on its own: **re-render
granularity** and **built-in tooling**.

### Problem 1: Context re-renders every consumer, not just the ones that care

A `Context.Provider` hands out **one value**. When that value changes, **every**
component calling `useContext` on it re-renders — even ones that only read a part of
the value that didn't actually change.

```tsx
// CartContext.tsx
const CartContext = createContext<{ user: string; cartCount: number } | null>(null);

function CartProvider({ children }: { children: React.ReactNode }) {
  const [cartCount, setCartCount] = useState(0);
  const [user] = useState("Anna");

  useEffect(() => {
    const id = setInterval(() => setCartCount(c => c + 1), 1000); // changes often
    return () => clearInterval(id);
  }, []);

  return (
    <CartContext.Provider value={{ user, cartCount }}>
      {children}
    </CartContext.Provider>
  );
}
```

```tsx
// UserBadge.tsx — only ever reads "user", never "cartCount"
function UserBadge() {
  const { user } = useContext(CartContext)!;
  console.log("UserBadge rendered"); // fires every second anyway
  return <span>{user}</span>;
}
```

`value={{ user, cartCount }}` is a **new object on every render**, so every consumer of
`CartContext` — including `UserBadge`, which never touches `cartCount` — re-renders once
a second. Context has no built-in way to say "only notify me if `user` changes."

You can work around this by splitting state into several small contexts (one for
`user`, one for `cart`), so unrelated consumers stop re-rendering together — but now
you're maintaining several providers nested in your tree, and remembering which piece of
state lives in which context. That gets unwieldy as an app grows — sometimes called
**"provider hell."**

### How Redux solves the same problem

```tsx
function UserBadge() {
  const user = useSelector((state: RootState) => state.user.name);
  console.log("UserBadge rendered"); // only fires when state.user.name actually changes
  return <span>{user}</span>;
}
```

`react-redux`'s `useSelector` subscribes to the **result of the selector function**, not
to the whole store. It compares the selected value between renders (by reference, by
default) and only re-renders the component if *that specific value* changed — no matter
how much of the rest of the store changed at the same time. You get the same fix as the
split-contexts workaround, "for free," from **one** store, without splitting anything.

### Problem 2: Context gives you no tooling, middleware, or structure

- **No DevTools.** Redux DevTools shows every dispatched action, the state before and
  after it, and lets you "time-travel" back to any previous state — invaluable for
  debugging. Plain Context has no equivalent; you're back to `console.log`.
- **No middleware.** Redux has a formal place to hook into every action — logging,
  analytics, auth checks, or async orchestration (`redux-thunk`, `redux-saga`,
  `createAsyncThunk`). With Context you'd hand-build all of that yourself, per project.
- **No enforced structure.** Redux Toolkit's `createSlice` gives a team the same shape
  for state, actions, and reducers everywhere, and `configureStore` combines many slices
  into one store. With Context you're free to structure things however you like — which
  means every codebase ends up doing it differently.
- **Reducers are trivially testable.** A reducer is a pure function:
  `(state, action) => newState`. You can unit test it with plain input/output
  assertions — no rendering, no mocking a provider.

### So when is Context actually fine?

- State that rarely changes (theme, locale, logged-in user) and isn't read by dozens of
  components at once.
- Small apps where the extra dependency and boilerplate of Redux genuinely aren't worth
  it.
- Passing something down a **short**, known chain of components — the exact
  "avoid prop drilling" case Context was built for.

Reach for Redux once state changes **often** and is read by **many, unrelated**
components — that's exactly the combination Context handles worst.

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

**Why does changing one field in a Context value re-render components that don't use
that field?**
A `Context.Provider` has one value as a whole — consumers subscribe to that whole value,
not to individual fields inside it. If the provider recreates the value object on every
update (which is normal), every consumer re-renders, whether or not the field they
actually read changed.

**How does `useSelector` avoid the re-render problem Context has?**
It subscribes to the *result* of the selector function, not to the whole store, and
compares that specific result between renders. A component only re-renders when the
exact slice of state it selected actually changes, no matter what else changed
elsewhere in the store.

**What problem does Redux solve that `useContext` alone doesn't handle well?**
Two things: re-render granularity (Context re-renders every consumer on any change;
Redux lets each component subscribe to just the slice it needs) and built-in tooling
(DevTools, middleware, an enforced structure via slices) that Context doesn't provide.

**Walk through the Redux data flow.**
A component calls `dispatch(action)`. The store passes the current state and that action
to the reducer, a pure function that returns the new state — never mutating the old one.
The store saves the new state and notifies subscribed components, which re-render.

**How do you handle asynchronous logic (like an API call) in Redux?**
Reducers have to stay synchronous and pure, so async logic lives outside them — in Redux
Toolkit, `createAsyncThunk` dispatches pending/fulfilled/rejected actions automatically
around a promise; a slice's `extraReducers` updates loading/data/error state in response.

**When would you NOT reach for Redux?**
For a small app, or state that's mostly local to one part of the tree, or state that
rarely changes and isn't read by many components — `useState` plus maybe `useContext` is
simpler and has no extra dependency. Redux earns its complexity when a lot of unrelated
components need to read/update the same, frequently-changing state predictably.
