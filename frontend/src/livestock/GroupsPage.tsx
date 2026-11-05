import { useState } from 'react';
import { api } from '../api/client';
import { useTenant } from '../auth/useCurrentTenant';
import { ErrorBanner } from '../components/common';
import { useNs } from '../i18n';
import { describeError } from '../lib/errors';
import { can } from '../lib/nav';
import { CollectManure } from '../organic/BatchesPage';
import { useGroups } from './LivestockLayout';
import type { Purpose } from './types';
import { PURPOSES } from './types';

/** Animal groups (I2): each is a cost object for feed, medicine, labour and depreciation. */
export default function GroupsPage() {
  const tenant = useTenant();
  const t = useNs('livestock');
  const base = `/api/v1/tenants/${tenant.id}/livestock/groups`;
  const canWrite = can(tenant, 'LIVESTOCK_WRITE');
  const { groups, reload } = useGroups(true);
  const [form, setForm] = useState({ name: '', purpose: 'MILKING' as Purpose });
  const [error, setError] = useState('');

  async function act(action: () => Promise<unknown>) {
    setError('');
    try { await action(); reload(); } catch (e) { setError(describeError(e)); }
  }

  return (
    <>
      <div className="crop-page-head"><div><h2>{t('groups')}</h2><p>{t('groupsHelp')}</p></div></div>
      <ErrorBanner message={error} />
      {canWrite && (
        <form className="inline-form" onSubmit={e => {
          e.preventDefault();
          if (!form.name.trim()) { setError(t('errors.groupName')); return; }
          void act(() => api(base, { method: 'POST', body: { name: form.name.trim(), purpose: form.purpose } }).then(() => setForm({ name: '', purpose: form.purpose })));
        }}>
          <label>{t('groupName')}<input maxLength={140} placeholder={t('groupExample')} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>{t('purposeLabel')}<select value={form.purpose} onChange={e => setForm({ ...form, purpose: e.target.value as Purpose })}>
            {PURPOSES.map(p => <option key={p} value={p}>{t(`purpose.${p}`)}</option>)}</select></label>
          <button className="secondary">{t('addGroup')}</button>
        </form>
      )}
      {groups.length === 0 ? <p className="hint">{t('noGroups')}</p> : (
        <div className="table-scroll"><table className="data-table">
          <thead><tr><th>{t('groupName')}</th><th>{t('purposeLabel')}</th><th className="num">{t('animals')}</th><th /></tr></thead>
          <tbody>{groups.map(g => (
            <tr key={g.id}>
              <td>{g.name}{!g.active && <small> · {t('closed')}</small>}</td><td>{t(`purpose.${g.purpose}`)}</td><td className="num">{g.animals}</td>
              <td>{canWrite && <button type="button" className="text-action" onClick={() => void act(() => api(`${base}/${g.id}`, { method: 'PATCH', body: { active: !g.active } }))}>
                {g.active ? t('closeGroup') : t('reopenGroup')}</button>}</td>
            </tr>
          ))}</tbody>
        </table></div>
      )}
      <CollectManure />
    </>
  );
}
