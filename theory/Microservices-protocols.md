# Protocols Between Microservices

When microservices talk to each other, they need a **protocol** — an agreed way to send
requests and responses over the network. This note compares the most common ones:
**HTTP, HTTPS, SOAP, and Thrift** (plus REST and gRPC, which you will hear about too).

---

## Quick comparison

| Protocol | Style              | Data format            | Speed     | Typical use                        |
|----------|--------------------|------------------------|-----------|------------------------------------|
| HTTP     | request/response   | anything (often JSON)  | medium    | base layer for REST / web APIs     |
| HTTPS    | HTTP + TLS         | same as HTTP, encrypted| medium    | any HTTP traffic that must be safe |
| SOAP     | strict XML messages| XML only               | slow      | old enterprise / banking systems   |
| Thrift   | binary RPC         | compact binary         | very fast | high-throughput internal services  |
| REST     | HTTP + conventions | usually JSON           | medium    | public APIs, most web backends     |
| gRPC     | binary RPC (HTTP/2)| Protobuf binary        | very fast | modern internal microservices      |

> **RPC** = Remote Procedure Call: you call a function on another service as if it were
> local (`userService.getUser(5)`), and the framework handles the network.

---

## HTTP

**HTTP** (HyperText Transfer Protocol) is the base protocol of the web. One side sends a
**request** (method + URL + headers + body), the other returns a **response** (status code
+ headers + body). It is **text-based** and **stateless** (each request is independent).

Common methods: `GET` (read), `POST` (create), `PUT` (replace), `PATCH` (update part),
`DELETE` (remove). Common status codes: `200 OK`, `201 Created`, `400 Bad Request`,
`401 Unauthorized`, `404 Not Found`, `500 Internal Server Error`.

```http
GET /api/users/5 HTTP/1.1
Host: example.com
Accept: application/json
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "id": 5, "name": "Anna" }
```

**Pros:** simple, universal, works everywhere, easy to debug and cache.
**Cons:** text format is larger/slower than binary; not encrypted by itself.

---

## HTTPS

**HTTPS** is just **HTTP over TLS** (formerly SSL). It is the **same HTTP** but the
connection is **encrypted**, so nobody on the network can read or change the data.

It gives you three things:

- **Encryption** — data is unreadable to anyone in the middle.
- **Integrity** — data cannot be changed without detection.
- **Authentication** — a certificate proves the server is who it claims to be.

> HTTPS is not a different API style — it is HTTP plus security. Today **everything**
> should use HTTPS, including traffic between internal microservices.

---

## SOAP

**SOAP** (Simple Object Access Protocol) is an **old, strict, XML-based** protocol. Every
message is an XML **envelope** with a header and a body. It is described by a **WSDL** file
(a contract that lists the operations and types).

```xml
<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
  <soap:Body>
    <getUser>
      <id>5</id>
    </getUser>
  </soap:Body>
</soap:Envelope>
```

**Pros:** strong, formal contract (WSDL); built-in standards for security and
transactions (WS-Security); good for strict enterprise rules.
**Cons:** verbose XML (slow, heavy); complex; mostly considered **legacy** today. You will
still meet it in **banking, government, and old enterprise** systems.

---

## Thrift

**Apache Thrift** is a **binary RPC** (**R**emote **P**rocedure **C**all) **framework**
(created at Facebook). You define your
services and data types once in an **IDL** file (`.thrift`), then Thrift **generates client
and server code** for many languages (Java, Python, C++, Go, …).

```thrift
struct User {
  1: i32 id
  2: string name
}

service UserService {
  User getUser(1: i32 id)
}
```

Because data travels as **compact binary** (not text), Thrift is **much smaller and
faster** than JSON/XML over HTTP. It is built for fast **service-to-service** calls inside
a company.

**Pros:** very fast, small messages, strongly typed, many languages from one IDL.
**Cons:** binary is hard to read/debug; not browser-friendly; extra build step to generate
code; less common than gRPC today.

---

## REST and gRPC (you should know these too)

- **REST** is not a protocol but a **style on top of HTTP**: resources have URLs
  (`/users/5`), HTTP methods mean actions, data is usually JSON. It is the **default
  choice** for most web and public APIs — simple and well understood.
- **gRPC** is Google's modern **binary RPC** (**R**emote **P**rocedure **C**all) over
  **HTTP/2**, using **Protobuf** for the message format. It is essentially an **analog of
  Thrift**: like Thrift, it generates client + server code from a contract (IDL) and is
  very fast; unlike Thrift it runs on HTTP/2 and also supports **streaming**. Today gRPC is
  the usual pick for **internal microservices**, with Thrift seen mostly in older or
  Facebook-influenced stacks.

---

## How to choose (interview answer)

- **Public API / browser clients** → **REST over HTTPS** (JSON). Simple and universal.
- **Internal, high-traffic service-to-service** → **gRPC** (or **Thrift**). Binary RPC is
  faster and smaller.
- **Old enterprise / banking integration** → you may be forced into **SOAP**.
- **Always use HTTPS**, never plain HTTP, for anything leaving the process.

> One-line summary: HTTP/HTTPS + REST = easy and universal; Thrift/gRPC = fast binary RPC
> for internal services; SOAP = heavy XML, mostly legacy.
