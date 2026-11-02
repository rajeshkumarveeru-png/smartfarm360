import { type CSSProperties, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { AlertTriangle, ChevronRight, Inbox, LoaderCircle, Search, X } from 'lucide-react';
import { useTranslation } from 'react-i18next';

/** Colour of a pop-up and of its tiles and rows. */
export type Tone = 'emerald' | 'sky' | 'amber' | 'violet' | 'rose' | 'teal' | 'indigo' | 'slate';
export const TONES: Tone[] = ['emerald', 'sky', 'amber', 'violet', 'rose', 'teal', 'indigo', 'slate'];

export type SummaryTile = { label: string; value: ReactNode; tone?: Tone; hint?: string };

/**
 * The pop-up behind a number: a coloured header in the card's tone, summary tiles, an optional toolbar and a body.
 * Esc, the close button or a click outside closes it, and focus returns to what opened it. On phones it is a bottom
 * sheet. Rendered on the document body so it sits above the side bar.
 */
export function InsightModal({ tone = 'emerald', icon, eyebrow, title, subtitle, summary, toolbar, children, footer, onClose }: {
  tone?: Tone; icon?: ReactNode; eyebrow?: string; title: string; subtitle?: string; summary?: SummaryTile[];
  toolbar?: ReactNode; children: ReactNode; footer?: ReactNode; onClose: () => void;
}) {
  const { t } = useTranslation();
  const closeRef = useRef<HTMLButtonElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    closeRef.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onCloseRef.current(); };
    window.addEventListener('keydown', onKey);
    return () => { document.body.style.overflow = overflow; window.removeEventListener('keydown', onKey); opener?.focus?.(); };
  }, []);

  return createPortal(
    <div className="im-overlay" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={`im-dialog im-${tone}`} role="dialog" aria-modal="true" aria-labelledby="im-title">
        <header className="im-head">
          <span className="im-head-glow" aria-hidden />
          {icon && <span className="im-icon" aria-hidden>{icon}</span>}
          <div className="im-head-copy">
            {eyebrow && <span className="im-eyebrow">{eyebrow}</span>}
            <h2 id="im-title">{title}</h2>
            {subtitle && <p>{subtitle}</p>}
          </div>
          <button ref={closeRef} type="button" className="im-close" onClick={onClose} aria-label={t('common.close')}><X size={18} aria-hidden /></button>
        </header>
        {summary && summary.length > 0 && (
          <div className="im-summary" style={{ '--n': summary.length } as CSSProperties}>
            {summary.map((s, i) => (
              <div key={s.label} className={`im-tile im-${s.tone ?? tone}`} style={{ animationDelay: `${i * 50}ms` }}>
                <span>{s.label}</span><strong>{s.value}</strong>{s.hint && <small>{s.hint}</small>}
              </div>
            ))}
          </div>
        )}
        {toolbar && <div className="im-toolbar">{toolbar}</div>}
        <div className="im-body">{children}</div>
        {footer && <footer className="im-foot">{footer}</footer>}
      </div>
    </div>,
    document.body,
  );
}

/** Loading, empty or error, inside a pop-up. */
export function InsightState({ kind, text }: { kind: 'loading' | 'empty' | 'error'; text: string }) {
  return (
    <div className={`im-state im-state-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      <span className="im-state-icon" aria-hidden>{kind === 'loading' ? <LoaderCircle size={22} className="im-spin" /> : kind === 'error' ? <AlertTriangle size={22} /> : <Inbox size={22} />}</span>
      <span>{text}</span>
    </div>
  );
}

export type InsightRow = {
  key: string | number; title: string; sub?: string; value?: string; valueSub?: string; valueTone?: 'positive' | 'negative';
  badge?: string; tone?: Tone; avatar?: ReactNode;
  /** 0–100: a share bar under the row */
  share?: number;
  onClick?: () => void; href?: string;
};

/** A colourful list for a pop-up, searchable when it is long. Rows with onClick or href open more detail. */
export function InsightList({ rows, empty, renderLink }: { rows: InsightRow[]; empty: string; renderLink?: (href: string, content: ReactNode, className: string) => ReactNode }) {
  const { t } = useTranslation();
  const [q, setQ] = useState('');
  const shown = useMemo(() => {
    const n = q.trim().toLowerCase();
    return n ? rows.filter(r => `${r.title} ${r.sub ?? ''} ${r.badge ?? ''} ${r.value ?? ''}`.toLowerCase().includes(n)) : rows;
  }, [rows, q]);
  if (rows.length === 0) return <InsightState kind="empty" text={empty} />;
  return (
    <>
      {rows.length > 6 && (
        <label className="im-search"><Search size={15} aria-hidden />
          <input value={q} onChange={e => setQ(e.target.value)} placeholder={t('common.search')} aria-label={t('common.search')} />
          <small>{shown.length} / {rows.length}</small>
        </label>
      )}
      <div className="im-list" role="list">
        {shown.map((r, i) => {
          const tone = r.tone ?? TONES[i % TONES.length];
          const content = <>
            <span className={`im-avatar im-${tone}`} aria-hidden>{r.avatar ?? (r.title.trim()[0] ?? '·').toUpperCase()}</span>
            <span className="im-row-main"><strong>{r.title}</strong>{r.sub && <small>{r.sub}</small>}
              {r.share != null && <span className={`im-share im-${tone}`} aria-hidden><span style={{ width: `${Math.max(2, Math.min(100, r.share))}%` }} /></span>}</span>
            {r.badge && <span className={`im-badge im-${tone}`}>{r.badge}</span>}
            {r.value != null && <span className="im-row-value"><b className={r.valueTone ?? ''}>{r.value}</b>{r.valueSub && <small>{r.valueSub}</small>}</span>}
            {(r.onClick || r.href) && <ChevronRight className="im-chevron" size={16} aria-hidden />}
          </>;
          const cls = `im-row im-${tone}`;
          return (
            <div key={r.key} role="listitem" className={r.href || r.onClick ? `im-item im-${tone}` : undefined}>
              {r.href && renderLink ? renderLink(r.href, content, cls)
                : r.onClick ? <button type="button" className={cls} onClick={r.onClick}>{content}</button>
                : <div className={cls}>{content}</div>}
            </div>
          );
        })}
      </div>
    </>
  );
}
