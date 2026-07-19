# Interview Notes — Middle Fullstack (17.02.2025)

## JS base

**1. How to declare a variable in JavaScript?**
`+` (too easy)

**2. What is the difference between `var`, `let` and `const`?**
`+` (too easy)

**3. What are arrow functions? Where are they used?**
`+` (too easy)

**4. If we use `this` inside an arrow function and pass that function as a callback to another component, what does `this` refer to?**
`+` (too easy)

## CSS base

**1. What techniques do you know for centering an element on a page?**
`+` (too easy)

**2. What is CSS specificity? What does it depend on?**
- Depends on the weight of the rules (which selector "wins").
```css
/* rough specificity order: inline style > id > class/attribute > element */
#header { color: red; }      /* higher specificity */
.title  { color: blue; }     /* lower specificity */
```

## React

**1. What is React?**
- A library (not a framework) for building user interfaces.

**2. What is Virtual DOM?**
- A copy (representation) of the real DOM kept in memory, used to calculate the minimal set of changes before updating the real DOM.

**3. What are hooks in React? How can they be used?**
`+` (too easy)

**4. Tell about `useState`.**
`+` (too easy)

**5. Tell about `useEffect`.**
`+` (too easy)

**6. How can `useEffect` be implemented so it runs only once?**
`+` (too easy)

**7. What is Redux?**
`+` (too easy)

## Java

**1. What is SOLID? Describe every letter.**
`+` (too easy)

**2. GoF patterns — which patterns have you seen in your projects? Have you implemented any of them?**
- Builder, Abstract Factory, Factory Method, Singleton, Prototype, Proxy, Decorator (for extensions, including extension points).

**3. What is the difference between Decorator and Proxy?**
- A Decorator requires an instance of the interface it is wrapping, while a Proxy does not require such an instance.
- A Proxy can receive an instance, but it's also allowed to create this instance itself.
```java
// Decorator - always wraps an existing instance
public class LoggingService implements Service {
    private final Service wrapped; // required
    public LoggingService(Service wrapped) { this.wrapped = wrapped; }
    public void run() {
        System.out.println("before");
        wrapped.run();
    }
}

// Proxy - can create the instance itself (e.g. lazy init)
public class LazyServiceProxy implements Service {
    private Service real; // not required upfront
    public void run() {
        if (real == null) real = new RealService(); // created on demand
        real.run();
    }
}
```

**4. How to create an immutable object?**
- Make a defensive copy of any objects passed in, or make sure those nested objects are also immutable.

**5. What is a marker interface?**
`+` (too easy)

**6. What is `Optional`? When do we use it?**
`+` (too easy)

**7. What happens when we pass `Optional` as a method argument?**
- Considered a bad practice (anti-pattern). `Optional` was designed as a **return type**, not a parameter type.
- Problems:
    - Caller must wrap value in `Optional`, even when it's simple.
    - `Optional` itself can be `null` — so you still need a null-check, just for a different type.
    - It doesn't work well with frameworks (e.g. serialization, JPA entities).
    - Makes method overloading harder.

**8. What is the exceptions hierarchy in Java?**
`+` (too easy)

**9. When should we use checked vs unchecked exceptions? What about our own custom exceptions?**
`+` (too easy)

**10. `try-catch-finally`?**
`+` (too easy)

**11. Collections hierarchy? `ArrayList` vs `LinkedList`?**
`+` (too easy)

**12. What are functional interfaces? Can an interface with only one default method (and no abstract method) be a functional interface? Examples of functional interfaces in Java?**
- No — a functional interface must have exactly one **abstract** method. `@FunctionalInterface` can also have any number of default/static methods.
```java
@FunctionalInterface
interface Calculator {
    int calculate(int a, int b); // one abstract method

    default void printInfo() {   // default methods are fine
        System.out.println("calculator");
    }
}
```
- Built-in examples: `Runnable`, `Comparator<T>`, `Function<T,R>`, `Supplier<T>`, `Predicate<T>`.

**13. Stream API — how does it work? What is the order of method calls? `.map()` vs `.flatMap()`?**
- Elements are processed one by one, not method by method — each element goes through the whole pipeline before the next element starts.
```java
Stream.of(1, 2, 3)
    .filter(n -> { System.out.println("filter: " + n); return n > 1; })
    .map(n -> { System.out.println("map: " + n); return n * 2; })
    .forEach(n -> System.out.println("forEach: " + n));
// filter:1, filter:2, map:2, forEach:4, filter:3, map:3, forEach:6
```
- If there is a short-circuiting terminal operation (`findFirst()`, `findAny()`, `limit()`), the stream processes only as many elements as it needs:
```java
Stream.of(1, 2, 3, 4, 5)
    .filter(n -> { System.out.println("checking: " + n); return n % 2 == 0; })
    .findFirst()
    .ifPresent(n -> System.out.println("found: " + n));
// checking:1, checking:2, found:2 -> never checks 3, 4, 5
```

**14. Can a stream contain more than 1 terminal operation?**
- No, one stream = one terminal operation. After the terminal operation runs, the stream is closed and can't be reused.
```java
Stream<Integer> stream = Stream.of(1, 2, 3);
stream.forEach(System.out::println); // ok
stream.count(); // IllegalStateException: stream has already been operated upon or closed
```

## Spring

**1. Inversion of Control vs Dependency Injection?**
- DI is an implementation of IoC in Spring. The main idea: delegate the creation of your logic classes' objects to a container that manages them (beans).

**2. How to inject a specific bean by its name?**
- `@Qualifier`
```java
@Autowired
public OrderService(@Qualifier("emailValidator") Validator validator) { ... }
```

**3. Why is constructor injection the recommended way? Why is it better than field `@Autowired` or setter injection?**
- The field can be `final` and can't be changed later by mistake.
- The object is created only once all its dependencies are ready.
- No reflection is needed to inject it.
- Spring throws an error at startup if there's a circular dependency with constructor injection. With setter/field injection, Spring can sometimes "resolve" the circular dependency using a proxy — but that just hides a real design problem.

**4. Can you inject a list of beans, like `List<Validator> validators`?**
- Yes, Spring automatically finds all implementations of the `Validator` interface and injects them into this list.
```java
public OrderService(List<Validator> validators) { this.validators = validators; }
```

**5. What is a circular dependency? How do you solve it?**
- `ServiceA` has a dependency (injected bean field) on `ServiceB`, and `ServiceB` has a dependency on `ServiceA`.
- Best way: redesign the app to avoid the circular dependency in the first place.
- Use `ObjectProvider<ServiceB>` and fetch the bean only when needed:
```java
ObjectProvider<ServiceB> serviceBProvider;
ServiceB serviceB = serviceBProvider.getObject();
```
- Use `@Lazy ServiceB serviceB` — Spring creates a proxy first, the real bean is loaded only when it's actually used.
- Inject via setter or field `@Autowired` — works, but hides the design problem.

## JPA

**1. What is JPA?**
- JPA = a specification (a set of rules, written as Java interfaces and annotations). Hibernate implements it (e.g. via `EntityManager`).

**2. What are the minimal steps needed to create an `@Entity` class?**
- Annotate the class with `@Entity`.
- Add an `@Id` field.
- Add a no-args constructor.
```java
@Entity
public class User {
    @Id
    @GeneratedValue
    private Long id;

    public User() {} // no-args constructor
}
```

**3. Can we add a field to an entity without storing it in the DB?**
- Yes, with `@Transient`. Usually its value is computed from the entity's other fields.
```java
@Entity
public class User {
    private String firstName;
    private String lastName;

    @Transient
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
```

**4. N+1 problem? How to solve it?**
- Happens when we select 100 users with 1 query, and then select the `List<Order>` for each user separately (N extra queries), instead of fetching everything in one query.
- Solutions:
```java
// JPQL
@Query("SELECT u FROM User u JOIN FETCH u.orders")
List<User> findAllWithOrders();

// EntityGraph
@EntityGraph(attributePaths = {"orders"})
List<User> findAll();

// QueryDSL - using .join(secondTable)
```

## API

**1. What is REST?**
`+` (too easy)

**2. Describe the API URIs for a "users" resource.**
`+` (too easy)

**3. What is idempotence?**
`+` (too easy)

## Architecture

**1. Microservices vs Monolith — what are the key advantages and disadvantages?**
- Microservices: more reliable/resilient (isolated failures) — if one service uses too many resources, only that service is affected, not the whole system; horizontal scaling is cheaper than vertical scaling; resources are distributed.
- Monolith: faster (no network calls between modules), no need to set up communication between services, easier to deploy and run.

## DB

**1. What is a transaction?**
`+` (too easy)

**2. Which isolation levels do you know?**
`+` (too easy)

**3. Which anomaly does Repeatable Read solve?**
- Non-repeatable read.

## Unit testing

**1. `@BeforeAll` vs `@BeforeEach`?**
- `@BeforeAll` runs once, at the beginning. `@BeforeEach` runs before every `@Test`.
```java
@BeforeAll
static void setupOnce() { }   // must be static

@BeforeEach
void setupEveryTime() { }
```

**2. `@Disabled("waiting for bug fix JIRA-123")` vs `@Ignore` (same purpose, but `@Ignore` is JUnit 4 and earlier)?**
- Marks a test to be temporarily skipped (not executed).
```java
@Test
@Disabled("waiting for bug fix JIRA-123")
void testCancelOrder() { }
```

**3. Parameterized tests?**
- One test method, run multiple times with different input parameters and expected output.
```java
@ParameterizedTest
@CsvSource({"2, 3, 5", "10, 5, 15"})
void testAdd(int a, int b, int expected) {
    assertEquals(expected, calculator.add(a, b));
}
```

**4. What is Mockito? Spy vs Mock?**
- Mockito = a library for creating fake objects in tests.
- `Mockito.mock(List.class)` — a fake object based on the class; it does nothing when you call its methods, unless you stub it: `when(mockList.size()).thenReturn(100);`
- `Mockito.spy(new ArrayList<>())` — wraps a real object. Even with `when(spyList.size()).thenReturn(100)`, Mockito first calls the real method, then overrides the result (risky for methods with side effects — for spies it's usually safer to use `doReturn().when()`).
```java
List<String> mockList = Mockito.mock(List.class);
when(mockList.size()).thenReturn(100);

List<String> spyList = Mockito.spy(new ArrayList<>());
doReturn(100).when(spyList).size(); // safer for spies
```
