# Spring / Spring Boot — Theory Notes

## 1. How does the AOP proxying mechanism work in Spring?

When you mark a method with an AOP annotation (`@Transactional`, `@Async`, `@Cacheable`, etc.), Spring creates a **proxy** for that bean — once, when the bean is created during application context startup (not on every call). The proxy is a wrapper object that holds the real (target) bean as a field.

When some external caller invokes the marked method through the bean reference from the Spring context, the call goes to the proxy first. The proxy adds the corresponding logic around the call — opens/commits/rolls back a transaction for `@Transactional`, runs the method on a separate thread for `@Async`, checks/populates a cache for `@Cacheable` — then delegates to the real method on the target object.

Two kinds of proxies:
- **JDK dynamic proxy** — used if the bean implements at least one interface.
- **CGLIB proxy** — a runtime-generated subclass of the target class, used if there's no interface. Because of this, `final` classes and `final` methods **cannot** be proxied by CGLIB.


Fixes:
- Call the marked method from **another bean**.
- Self-inject the bean (`@Autowired private OrderService self;`) and call `self.saveOrder()`.
- Use `AopContext.currentProxy()` (requires `exposeProxy = true`).

## 2. Spring vs Spring Boot

Spring Boot is an extension built on top of the Spring Framework. It provides:

### 2.1 Auto-configuration

In plain Spring, you configure everything yourself:
```java
@Configuration
@ComponentScan("com.example")
@EnableWebMvc
public class AppConfig {
}
```
You need to manually set up things like DispatcherServlet, Tomcat/Jetty, Jackson, logging, DataSource, transaction manager, etc.

With Spring Boot, most of this is auto-configured:
```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```
`@SpringBootApplication` is equivalent to `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan`.

If Spring Boot detects `spring-boot-starter-data-jpa` and, say, a PostgreSQL driver on the classpath, it automatically creates the corresponding beans: `DataSource`, `EntityManagerFactory`, `TransactionManager`, etc. Same idea applies to other starters.

### 2.2 Dependency management

Spring Boot starters automatically pick compatible versions for included dependencies, based on the Spring Boot version (via the Spring Boot BOM). This prevents version conflicts and some known vulnerabilities. In plain Spring, you manage every dependency version manually.

### 2.3 Embedded server

Spring Boot provides an embedded server (Tomcat, Jetty, etc.) by default, but can still be packaged as a WAR without it if needed.

### 2.4 Additional production features
- Spring Boot Actuator
- Health-check endpoint `/actuator/health`
- Integration with metrics systems (via Micrometer, e.g. exporting to Prometheus)
- Additional/simplified logging configuration

## 3. Spring Boot Actuator

Actuator is a library providing a set of production-ready, built-in HTTP endpoints that expose vital runtime metadata about the application.

By default, adding the Actuator dependency exposes endpoints like:
- `/actuator/health` — tells you (and e.g. Kubernetes) if the app is up and running.
- `/actuator/metrics` — memory usage, CPU usage, GC statistics, HTTP request counts.
- `/actuator/env` — environment variables and configuration properties.
- `/actuator/threaddump` / `/actuator/heapdump` — diagnostic tools for debugging JVM deadlocks or memory leaks.

You can restrict/hide these endpoints from unauthorized users (via Spring Security or `management.endpoints.web.exposure.include/exclude`), to keep sensitive data away from possible attackers. In practice, microservices are often deployed inside a private network with a single public entry point (a gateway) — so external attackers typically can't reach actuator endpoints directly, only internal/authorized traffic within that network can. Still, exposing sensitive endpoints like `/heapdump` without any protection is a real risk if an attacker gets into the internal network, so this shouldn't be relied on as the only safeguard.

---

## Control questions (self-check)

### AOP / Proxying
1. What happens if you call a `@Transactional` method from another method inside the same class?
-
2. Why doesn't `@Async` work on `private` methods?
-
3. JDK dynamic proxy vs CGLIB proxy — when is each one used?
-
4. Can a `final` class, or a class with `final` methods, be proxied? Why/why not?
-
5. Name two ways to fix the self-invocation problem.
-

### Spring vs Spring Boot
6. Can you exclude a specific auto-configuration class in Spring Boot? How?
-
7. If you define your own `DataSource` bean, will Spring Boot still create its own?
-
8. Can a Spring Boot app be deployed without the embedded server, as a WAR?
-
9. What determines whether an auto-configuration class actually creates its beans?
-

### Actuator
10. How do you expose only specific actuator endpoints (not all of them)?
-
11. Can actuator run on a different port than the main application?
-
12. Why can `/actuator/heapdump` be a security risk if exposed publicly?
-
