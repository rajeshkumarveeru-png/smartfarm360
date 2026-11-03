import { useCallback, useEffect, useState } from 'react';
import { RotateCcw, Trash2 } from 'lucide-react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { currentLanguage, useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { formatDateTime } from '../lib/format';
import { can } from '../lib/nav';

export type BinItem = { kind: string; id: number; name: string; detail: string | null; deletedAt: string; deletedBy: string | null };

/**
 * Settings → Bin (bin and batch varieties design, B1-B9): what was deleted, with Restore, and Delete forever for the
 * owner. Delete forever is refused while money or stock records refer to the item; it then stays here.
 */
export default function BinPanel() {
  const tenant = useTenant();
  const t = useNs('bin');
  const lang = currentLanguage();
  const base = `/api/v1/tenants/${tenant.id}/bin`;
  const canPurge = can(tenant, 'DATA_PURGE');
  const [items, setItems] = useState<BinItem[] | null>(null);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(() => {
    api<BinItem[]>(base).then(setItems).catch(e => setError(describeError(e)));
  }, [base]);
  useEffect(load, [load]);

  async function act(item: BinItem, action: 'restore' | 'purge') {
    if (action === 'purge' && !window.confirm(t('confirmPurge', { name: item.name }))) return;
    const key = `${item.kind}-${item.id}`;
    setBusy(key); setError(''); setNotice('');
    try {
      if (action === 'restore') await api(`${base}/${item.kind}/${item.id}/restore`, { method: 'POST' });
      else await api(`${base}/${item.kind}/${item.id}`, { method: 'DELETE' });
      setNotice(t(action === 'restore' ? 'restored' : 'purged', { name: item.name }));
      load();
    } catch (e) {
      setError(describeError(e));
    } finally {
      setBusy('');
    }
  }

  return (
    <section className="panel bin-panel">
      <h3><Trash2 size={18} aria-hidden />{t('title')}</h3>
      <p className="hint">{t('help')}</p>
      <ErrorBanner message={error} />
      {notice && <p className="info-banner" role="status">{notice}</p>}
      {items && items.length === 0 && <p className="hint bin-empty">{t('empty')}</p>}
      {items && items.length > 0 && (
        <ul className="bin-list">
          {items.map(item => {
            const key = `${item.kind}-${item.id}`;
            return (
              <li key={key}>
                <span className="bin-kind">{t(`kind.${item.kind}`)}</span>
                <span className="bin-main"><strong>{item.name}</strong>
                  <small>{[item.detail, t('deletedOn', { date: formatDateTime(item.deletedAt, lang) }), item.deletedBy].filter(Boolean).join(' · ')}</small></span>
                <span className="bin-actions">
                  <button type="button" className="secondary" disabled={busy === key} onClick={() => void act(item, 'restore')}>
                    <RotateCcw size={15} aria-hidden />{t('restore')}</button>
                  {canPurge && <button type="button" className="danger" disabled={busy === key} onClick={() => void act(item, 'purge')}>
                    <Trash2 size={15} aria-hidden />{t('purge')}</button>}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
