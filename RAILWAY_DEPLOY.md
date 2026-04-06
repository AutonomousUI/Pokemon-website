# Railway Deployment

## Runtime variables

Set these in your Railway service before the first deploy:

- `GROQ_API_KEY`: required only if you want AI decisions powered by Groq.
- `GROQ_MODEL`: optional, defaults to `llama-3.3-70b-versatile`.

## Deploy

1. Push this project to GitHub.
2. In Railway, create a new project and choose **Deploy from GitHub repo**.
3. Select this repository.
4. Add the environment variables above.
5. Deploy.

## Notes

- The app binds to Railway's injected `PORT` automatically.
- The static frontend now uses same-origin API and WebSocket URLs, so it works from the deployed domain.
- The default H2 database is in-memory, so data resets when the service restarts.
