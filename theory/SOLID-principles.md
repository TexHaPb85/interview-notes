# SOLID Principles

## What is SOLID? Why do we need it?

**SOLID** is a set of five principles recommended to follow during software development.

Following these principles keep code **easy to read, change, and extend**, make code navigation much easier,
significantly reduces number of potential bugs and stop classes from turning into
huge, tangled files that nobody wants to touch.

| Letter | Principle                     | One-line idea                                           |
|--------|-------------------------------|---------------------------------------------------------|
| **S**  | Single Responsibility         | one class = one functionality                           |
| **O**  | Open/Closed                   | open for extension, closed for modification             |
| **L**  | Liskov Substitution           | a subclass must work anywhere its parent works          |
| **I**  | Interface Segregation         | don't force a class to implement methods it doesn't use |
| **D**  | Dependency Inversion          | everything depend on abstractions, not concrete classes |

---

## S — Single Responsibility
> **One class should describe only one functionality logically follows from its name**

**Violated when** a class grows into hundreds of lines (e.g. 500+) and becomes hard to
navigate and read, usually because new methods were added without thinking, just to finish
tasks ASAP.

**Fix:** split the big class:
- by functionality;
- by logical layers;


For example, one class handles
**user-modification** operations in the DB, another handles **user-searching** operations.
If a split class later grows too big again, split it again.

```java
// ❌ One class doing too much — keeps growing to 400+ lines
class UserService {
    void createUser(User u) { /* ... */ }
    void updateUser(User u) { /* ... */ }
    void deleteUser(Long id) { /* ... */ }
    List<User> searchByName(String name) { /* ... */ }
    List<User> searchByEmail(String email) { /* ... */ }
}

// ✅ Split by responsibility
class UserModificationService {   // changes in DB
    void createUser(User u) { /* ... */ }
    void updateUser(User u) { /* ... */ }
    void deleteUser(Long id) { /* ... */ }
}

class UserSearchingService {      // reads from DB
    List<User> searchByName(String name) { /* ... */ }
    List<User> searchByEmail(String email) { /* ... */ }
}
```

> Note: line count (400–500) is just a **warning sign**, not the real rule. The real rule
> is **"one reason to change"** — if two different kinds of change would touch the same
> class, it probably has two responsibilities.

> Your project examples:
> - **FE:** `adminportalapp/src/services/util.js` — e.g. move out company-related methods.
> - **BE:** `adminportal2/.../web/controller/CompanyController.java`.

---

## O — Open/Closed

> **Code should be open for extension but closed(avoided) for modification.**

**Violated when** you modify existing code logic that`s already used in several places —
unless the task really requires changing the business logic in *all* of those places. 


*Actually it is violated often but still we should avoid such modifications.*

**Fix:** 

If the logic change is **not** required everywhere where method used, then - **extend** the existing class
and put your change in the subclass (or a new implementation), or wrap the method with another one and extend the logic,
then use that extension only in place where you need it.

If you see that in some place logic will be updated and code will growth with similar extensions - 
design this place in the way new implementation for new logical case.

```java
public record UserCreationRequest(String email, String username, String password) {}

class UserService {
    public User createUser(UserCreationRequest request) { //used in 3+ places in project
        User user = new User(request.username(), request.email(), request.password());
        return userRepository.save(user);
    }
}

/**
 * In case if we need to extend logic in ONE place where the method used - we wrap or extend and override it for one
 * particular usage without affecting other places, instead of modifying the method itself.
 */
public User createIfNotExists(UserCreationRequest request) { 
    boolean suchUserAlreadyExists = userRepository.existsByEmailOrUsername(request.email(), request.username());
    if (exists) {
        throw new UserAlreadyExistsException(request.email());
    }
    return userService.createUser(request); // calls the original, untouched method
}

```

```java
// ❌ if/else per type pattern
// Grows forever: new login type = edit this method again (one big risky class)
@Service
public class AuthService {

    public User authenticate(String type, String username, String password) {
        if (type.equals("DB")) {
            User user = userRepo.findByUsername(username);
            if (user == null || !encoder.matches(password, user.getPassword())) {
                throw new BadCredentialsException("Wrong credentials");
            }
            //100+ lines of logic
            return user;
        }
        if (type.equals("LDAP")) {
            DirContext ctx = ldapBind(username, password);
            if (ctx == null) throw new BadCredentialsException("LDAP bind failed");
            //100+ lines of logic
            return userFromLdap(ctx);
        }
        if (type.equals("OAUTH2")) {
            TokenInfo info = oauthClient.validate(password);
            if (!info.valid()) throw new BadCredentialsException("Bad token");
            //100+ lines of logic
            return userFromToken(info);
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
}


// ✅ Add new behavior without changing existing code
public interface AuthenticationProvider {
    Authentication authenticate(Authentication authentication) throws AuthenticationException;
    boolean supports(Class<?> authType);
}

// each class handles ONE auth type (bodies simplified here)
class DaoAuthenticationProvider implements AuthenticationProvider { ... }  // DB
class LdapAuthenticationProvider implements AuthenticationProvider { ... } // LDAP
class JwtAuthenticationProvider implements AuthenticationProvider { ... }  // JWT token
// New Authentication method? Add a new class — don't touch the old ones.

class ProviderManager implements AuthenticationManager {
    private final List<AuthenticationProvider> providers;

    public Authentication authenticate(Authentication auth) throws AuthenticationException {
        for (AuthenticationProvider p : providers) {
            if (!p.supports(auth.getClass())) continue;
            Authentication result = p.authenticate(auth);
            if (result != null) return result;
        }
        throw new ProviderNotFoundException("No provider for " + auth.getClass());
    }
}
```

---

## L — Liskov Substitution

> **A child class should extend (complement) the behavior of the parent, not replace it.**
> You should be able to use a subclass anywhere the parent is expected, without breaking
> anything.

**Violated when** inheritance forces you to **completely override** the parent's method
instead of extending it.

**Fix:** design inheritance so the subclass **calls the superclass logic and adds to it**
(before, after, or at a prepared extension point). If you must override the whole method,
better make a separate method or a separate branch in the inheritance tree.

```java
// ❌ Square "is a" Rectangle, but breaks the parent's contract
class Rectangle {
    protected int w, h;
    void setWidth(int w)  { this.w = w; }
    void setHeight(int h) { this.h = h; }
    int area() { return w * h; }
}

class Square extends Rectangle {
    void setWidth(int w)  { this.w = w; this.h = w; }  // surprise side effect
    void setHeight(int h) { this.w = h; this.h = h; }  // breaks code that set w and h
}
// Code that does setWidth(5); setHeight(4); expects area 20 — gets 16 with a Square.
```

> Note: the formal idea is **substitutability** — if code works with the parent type, it
> must keep working when you pass a subclass. The Square/Rectangle case shows a "wrong"
> inheritance: a square should not extend rectangle here.

---

## I — Interface Segregation

> **A class should not depend on methods (of an interface) that it does not use.**

This is like Single Responsibility, but for **interfaces**.

**Violated when** a class that implements an interface is forced to implement a method it
does not need (often left empty or throwing an exception).

**Fix:** if an interface has methods that some implementations don't use, **split** it into
smaller interfaces.

```java
// ❌ Fat interface forces Robot to implement eat()
interface Worker {
    void work();
    void eat();
}
class Robot implements Worker {
    public void work() { /* ok */ }
    public void eat()  { throw new UnsupportedOperationException(); } // doesn't eat!
}

// ✅ Split into focused interfaces
interface Workable { void work(); }
interface Eatable  { void eat(); }

class Human implements Workable, Eatable { /* both */ }
class Robot implements Workable { /* only work */ }
```

---

## D — Dependency Inversion

> **High-level modules should not depend on low-level modules — both should depend on
> abstractions.** Abstractions should not depend on details; details should depend on
> abstractions.

**Violated when** you use a **concrete implementation** instead of an abstraction as a
dependency (e.g. a class field). That locks you in and blocks future extension.

**Fix:** if you might need several implementations of the same functionality, depend on an
**abstraction** (interface) and **inject** the concrete one from outside.

```java
// ❌ High-level service is glued to one concrete repository
class OrderService {
    private MySqlOrderRepository repo = new MySqlOrderRepository(); // locked in
}

// ✅ Depend on an interface; the implementation is injected
interface OrderRepository { void save(Order o); }

class OrderService {
    private final OrderRepository repo;
    OrderService(OrderRepository repo) { this.repo = repo; } // constructor injection
}
```

> In Spring this is exactly what **dependency injection** does: you declare the interface,
> and Spring injects the implementation (`@Autowired` / constructor injection). Swapping
> `MySqlOrderRepository` for `MongoOrderRepository` then needs **no change** in
> `OrderService`.

---

## Quick recap (interview answer)

- **S** — one class, one job; split classes that grow too big or change for many reasons.
- **O** — add new behavior by **extending**, not by editing working code (use polymorphism).
- **L** — a subclass must be usable in place of its parent without surprises.
- **I** — keep interfaces small; don't force empty/unused method implementations.
- **D** — depend on **interfaces**, inject implementations (the core idea behind Spring DI).

---

*Source reference: <https://techwayfit.com/blogs/design-principles/solid-principles/>*
