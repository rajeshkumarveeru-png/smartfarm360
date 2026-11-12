import { NavLink } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import { useNs } from '../i18n';
import { can } from '../lib/nav';

/** Shared navigation for the farm, parcel and cultivation workflow. */
export default function FarmCropNav() {
  const tenant = useTenant();
  const farm = useNs('farm');
  const crop = useNs('crop');
  const base = `/t/${tenant.id}`;
  const tabs = [
    ...(can(tenant, 'FARM_READ') ? [{ to: `${base}/farms`, label: farm('farms'), end: true }] : []),
    ...(can(tenant, 'CROP_READ') && tenant.enabledModules.includes('CROP') ? [
      { to: `${base}/crops`, label: crop('cycles'), end: false },
      { to: `${base}/crops/setup`, label: crop('setup'), end: false },
      { to: `${base}/crops/weather`, label: crop('weather'), end: false },
    ] : []),
  ];
  return <nav className="crop-tabs farm-crop-tabs" aria-label="Farm and crop management">
    {tabs.map(tab => <NavLink key={tab.to} to={tab.to} end={tab.end}>{tab.label}</NavLink>)}
  </nav>;
}
