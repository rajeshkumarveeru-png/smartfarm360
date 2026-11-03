import { Navigate } from 'react-router-dom';
import { useTenant } from '../auth/useCurrentTenant';
import HerdPage from './HerdPage';

/** The livestock section opens on the dairy herd, or on the first enabled part without dairy (J11). */
export default function LivestockHome() {
  const tenant = useTenant();
  const on = (m: string) => tenant.enabledModules.includes(m as never);
  if (on('DAIRY')) return <HerdPage />;
  return <Navigate replace to={on('POULTRY') ? 'poultry' : on('GOAT_SHEEP') ? 'small-stock' : 'care'} />;
}
