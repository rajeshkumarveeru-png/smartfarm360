# SmartFarm360 Mobile

Expo React Native app for SmartFarm360. Users sign in (accounts are created in the web app), choose a farm business and record income/expense entries. Entries are kept on the phone per user and farm business until sync arrives (Phase 13). Strings come from `../shared/i18n` (Metro watches that folder).

## Run

```bash
npm install
npx expo start
```

Set `EXPO_PUBLIC_API_URL` in `.env` to the backend base URL, for example `http://192.168.1.10:8080`.

Tests: `npm test` (jest-expo). Refresh tokens are stored with `expo-secure-store`. Never use `localhost` from a physical phone unless the API runs on the phone itself.
