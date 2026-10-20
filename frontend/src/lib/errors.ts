import i18n from '../i18n';
import { ApiError } from '../api/client';

/** A user-facing, translated message for any error thrown by the API client. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'NETWORK') return i18n.t('errors.network');
    const key = `errors.${error.code}`;
    if (i18n.exists(key)) return i18n.t(key);
    // Older endpoints return author-written English details for validation errors; show them rather than nothing.
    if (error.status === 400 && error.message) return error.message;
  }
  return i18n.t('errors.generic');
}
