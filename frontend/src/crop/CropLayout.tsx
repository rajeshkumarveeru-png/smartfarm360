import { Outlet } from 'react-router-dom';
import FarmCropNav from '../components/FarmCropNav';

/** Crop pages remain on their established URLs but are presented as part of Farm & Crop Management. */
export default function CropLayout() {
  return <section className="crop-page finance-page">
    <FarmCropNav />
    <Outlet />
  </section>;
}
