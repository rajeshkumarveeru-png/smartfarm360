# SmartFarm360 Mobile

Expo React Native starter for SmartFarm360 farming workflows. This package contains only farming product code; the current mobile screens are a foundation and still need the authenticated crop-management API workflows wired in.

## Run

```bash
npm install
npx expo start
```

Set `EXPO_PUBLIC_API_URL` in `.env` to the backend base URL, for example `http://192.168.1.10:8080`. Never use `localhost` from a physical phone unless the API runs on the phone itself.
