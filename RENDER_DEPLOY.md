# Render Deployment

## Runtime variables

Set these in your Render web service:

- `GROQ_API_KEY`: required only if you want AI decisions powered by Groq.
- `GROQ_MODEL`: optional, defaults to `llama-3.3-70b-versatile`.

## Deploy

1. Push this project to GitHub.
2. In Render, create a new **Web Service** from this repository.
3. Use the included `render.yaml` blueprint, or configure these commands manually:
   Build Command: `chmod +x mvnw && ./mvnw -DskipTests clean package`
   Start Command: `java -jar target/battlesimulator-0.0.1-SNAPSHOT.jar`
4. Add the environment variables above.
5. Deploy.

## Notes

- Render injects `PORT`, and the app already binds to it automatically.
- The frontend uses same-origin API and WebSocket URLs, so it works from the Render domain.
- The default H2 database is in-memory, so data resets whenever the service restarts.
