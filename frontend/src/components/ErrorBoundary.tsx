import { Component, type ErrorInfo, type ReactNode } from 'react';
import { CircleAlert, RefreshCw } from 'lucide-react';
import { useTranslation } from 'react-i18next';

function CrashMessage({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="page-crash" role="alert">
      <CircleAlert size={26} aria-hidden />
      <h2>{t('common.crashTitle')}</h2>
      <p>{t('common.crashBody')}</p>
      <button type="button" className="primary" onClick={onRetry}><RefreshCw size={15} aria-hidden />{t('common.retry')}</button>
    </div>
  );
}

/** Keeps a fault in one page from blanking the whole app: the menu stays, and moving to another page ({@code resetKey}) clears it. */
export class ErrorBoundary extends Component<{ resetKey: string; children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error(error, info.componentStack);
  }

  componentDidUpdate(previous: { resetKey: string }) {
    if (previous.resetKey !== this.props.resetKey && this.state.failed) this.setState({ failed: false });
  }

  render() {
    return this.state.failed ? <CrashMessage onRetry={() => this.setState({ failed: false })} /> : this.props.children;
  }
}
