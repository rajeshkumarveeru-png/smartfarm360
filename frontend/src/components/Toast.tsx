import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { CheckCircle2, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { onSaved } from '../api/client';

/** Toasts share one stack on the document body, so two at once sit one above the other. */
function stack(): HTMLElement {
  let el = document.getElementById('toast-stack');
  if (!el) {
    el = document.createElement('div');
    el.id = 'toast-stack';
    el.className = 'toast-stack';
    document.body.appendChild(el);
  }
  return el;
}

// A page's own message says more than "Saved.", so it takes the place of the general one
let hideGeneral = () => {};

/** A confirmation that shows wherever the user has scrolled to, and goes away by itself; a longer message stays longer. */
export function Toast({ message, onClose, ms }: { message: string; onClose: () => void; ms?: number }) {
  const { t } = useTranslation();
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;
  useEffect(() => {
    if (!message) return undefined;
    hideGeneral();
    const timer = window.setTimeout(() => onCloseRef.current(), ms ?? Math.max(5000, message.length * 70));
    return () => window.clearTimeout(timer);
  }, [message, ms]);
  if (!message) return null;
  return createPortal(
    <div className="toast" role="status">
      <CheckCircle2 size={18} aria-hidden />
      <span>{message}</span>
      <button type="button" className="toast-close" onClick={onClose} aria-label={t('common.close')}><X size={15} aria-hidden /></button>
    </div>,
    stack(),
  );
}

/** Waits this long for the page's own message, which usually follows a reload of the page's data. */
const WAIT_FOR_PAGE_MS = 700;

/**
 * The confirmation for every save, mounted once at the root so it outlives a page that navigates away after saving. Pages
 * that say something more specific use {@link Toast}, which replaces this one.
 */
export function SavedToast() {
  const { t } = useTranslation();
  const [message, setMessage] = useState('');
  useEffect(() => {
    let timer: number | undefined;
    hideGeneral = () => { window.clearTimeout(timer); setMessage(''); };
    const off = onSaved(deleted => {
      window.clearTimeout(timer);
      timer = window.setTimeout(() => setMessage(t(deleted ? 'common.deleted' : 'common.saved')), WAIT_FOR_PAGE_MS);
    });
    return () => { off(); window.clearTimeout(timer); hideGeneral = () => {}; };
  }, [t]);
  useEffect(() => {
    if (!message) return undefined;
    const timer = window.setTimeout(() => setMessage(''), 4000);
    return () => window.clearTimeout(timer);
  }, [message]);
  if (!message) return null;
  return createPortal(
    <div className="toast" role="status">
      <CheckCircle2 size={18} aria-hidden />
      <span>{message}</span>
      <button type="button" className="toast-close" onClick={() => setMessage('')} aria-label={t('common.close')}><X size={15} aria-hidden /></button>
    </div>,
    stack(),
  );
}
