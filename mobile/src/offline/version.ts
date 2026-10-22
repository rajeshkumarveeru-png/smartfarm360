import app from '../../app.json';

/** The app version sent as X-Client-Version (mobile offline design, N14); it is app.json's version. */
export const APP_VERSION: string = app.expo.version;
