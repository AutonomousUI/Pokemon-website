# Render Deployment

## Runtime variables

Set these in your Render web service:

- `GROQ_API_KEY`: required only if you want AI decisions powered by Groq.
- `GROQ_MODEL`: optional, defaults to `llama-3.3-70b-versatile`.

## Deploy

1. Push this project to GitHub.
2. In Render, create a new **Web Service** from this repository.
3. If Render offers `Java`, use it with:
   Build Command: `chmod +x mvnw && ./mvnw -DskipTests clean package`
   Start Command: `java -jar target/battlesimulator-0.0.1-SNAPSHOT.jar`
4. If Render does not offer `Java`, choose `Docker` and let it build from the included `Dockerfile`.
5. Add the environment variables above.
6. Deploy.

## Notes

- Render injects `PORT`, and the app already binds to it automatically.
- The included `Dockerfile` works for Render's Docker runtime.
- The frontend uses same-origin API and WebSocket URLs, so it works from the Render domain.
- The default H2 database is in-memory, so data resets whenever the service restarts.
