# IsoVista Frontend

Vue 3 and Vite frontend for experiment configuration, run monitoring, bug lists, G6 counterexample graphs, and ECharts profiles.

## Prerequisites

- Node.js 20 and npm (`Dockerfile` currently uses Node 20.10.0)
- The FastAPI backend started from the repository root as described in [ROADMAP.md](../ROADMAP.md)

The backend URL is read from `VITE_BACKEND_URL`. The current `.env` uses:

```dotenv
VITE_BACKEND_URL=http://127.0.0.1:8000/
```

Use a URL reachable from the browser. Do not put database credentials in frontend environment files.

## Commands

Run these commands from `frontend/`:

```bash
# Install the locked dependency versions
npm ci

# Start the Vite development server
npm run dev

# Create a production build
npm run build

# Preview the production build locally
npm run preview
```

## Code map

- `src/components/Configuration.vue`: database, workload, and checker configuration.
- `src/components/RunList.vue` and `RunView.vue`: runs, logs, profiles, and downloads.
- `src/components/BugList.vue` and `BugView.vue`: bug tags, graph rendering, expansion, and export.
- `src/api/api.js`: backend endpoint wrappers.
- `src/utils/request.js`: Axios instance and backend base URL.

When changing an API or graph DTO, update the backend producer and all consuming components together. Run `npm run build` after frontend changes; a production build does not replace API or Web end-to-end smoke testing.
