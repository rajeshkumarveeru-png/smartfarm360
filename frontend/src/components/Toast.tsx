import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';

/** A confirmation that shows wherever the user has scrolled to, and goes away by itself. Rendered on the document body. */
export function Toast({ message, onClose, ms = 5000 }: { message: string; onClose: () => void; ms?: number }) {
  const { t } = useTranslation();
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    if (!message) return undefined;
    const timer = window.setTimeout(() => onCloseRef.current(), ms);
    return () => window.clearTimeout(timer);
  }, [message, ms]);
  if (!message) return null;
  return createPortal(
    <div className="toast" role="status">
      <CheckCircle2 size={18} aria-hidden />
      <span>{message}</span>
      <button type="button" className="toast-close" onClick={onClose} aria-label={t('common.close')}><X size={15} aria-hidden /></button>
    </div>,
    document.body,
  );
}
