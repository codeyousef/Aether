# Deployment

Aether applications can be deployed to various environments thanks to Kotlin Multiplatform.

## JVM Deployment

The JVM target is the most feature-complete and performant for traditional server environments.

### Fat JAR

You can build a self-contained JAR file (Fat JAR / Shadow JAR) that includes all dependencies.

```bash
./gradlew :example-app:shadowJar
```

Run it:
```bash
java -jar example-app/build/libs/example-app-all.jar
```

### Docker

Create a `Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/example-app-all.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

Build and run:
```bash
docker build -t my-aether-app .
docker run -p 8080:8080 my-aether-app
```

## Wasm Deployment

Wasm support allows Aether to run in edge environments or even in the browser (Service Workers).

### Cloudflare Workers (Experimental)

1.  Build the Wasm binary:
    ```bash
    ./gradlew :example-app:wasmJsBrowserDistribution
    ```
2.  Use `wrangler` to deploy the generated `.wasm` and `.js` files.

### Node.js

You can run the Wasm output directly in Node.js:

```bash
node build/compileSync/wasmJs/main/productionExecutable/kotlin/example-app.js
```

## Configuration

Use environment variables to configure your application in production.

```kotlin
val port = System.getenv("PORT")?.toInt() ?: 8080
val dbUrl = System.getenv("DATABASE_URL")
```
