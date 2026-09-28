"""Explicit delivery mapping and optional network access, conserving parcel counts."""

from pathlib import Path
import xml.etree.ElementTree as ET
import gzip
import geopandas as gpd
import pandas as pd
from shapely.geometry import LineString


def network_links(path, modes, crs):
    nodes, links = {}, []
    opener=gzip.open if str(path).endswith('.gz') else open
    with opener(path,'rb') as stream:
        # Clear completed network sections; node/link children carry no geometry needed later.
        for event, element in ET.iterparse(stream,events=['end']):
            tag=element.tag.rsplit('}',1)[-1]
            a=element.attrib
            if tag=='node': nodes[a['id']]=(float(a['x']),float(a['y']));element.clear()
            elif tag=='link':
                allowed=set(a.get('modes','').split(','))
                if allowed.intersection(modes) and a.get('from') in nodes and a.get('to') in nodes:
                    links.append({'network_link':a['id'],'geometry':LineString([nodes[a['from']],nodes[a['to']]])})
                element.clear()
            elif tag in {'nodes','links'}: element.clear()
    if not links: raise ValueError('No eligible network links; check modes, coordinate system and source')
    return gpd.GeoDataFrame(links,geometry='geometry',crs=crs)


def build_delivery_access(sites,cfg):
    points=sites[['site_id','geometry']].copy()
    points['delivery_point_id']=points.site_id
    points['carrier']='*'
    points['point_status']='source_location_proxy_not_verified_entrance'
    if cfg.get('delivery_mapping'):
        mapping=pd.read_csv(cfg['delivery_mapping'],dtype={'site_id':str,'carrier':str,'delivery_point_id':str})
        required={'site_id','carrier','delivery_point_id','x','y'}
        if not required<=set(mapping) or mapping.duplicated(['site_id','carrier']).any():
            raise ValueError('Delivery mapping requires unique site/carrier with point ID and x/y in site CRS')
        if not set(mapping.site_id)<=set(sites.site_id): raise ValueError('Unknown mapped site IDs')
        replacements=gpd.GeoDataFrame(mapping,geometry=gpd.points_from_xy(mapping.x,mapping.y),crs=sites.crs)
        if replacements.geometry.isna().any() or not replacements.is_valid.all(): raise ValueError('Invalid mapped delivery coordinates')
        replacements['point_status']='explicit_user_mapping'
        points=pd.concat([points,replacements[points.columns]],ignore_index=True)
        points=gpd.GeoDataFrame(points,geometry='geometry',crs=sites.crs)
    # A destination ID must describe one physical location across all mappings.
    if points.assign(wkt=points.geometry.to_wkt()).groupby('delivery_point_id').wkt.nunique().gt(1).any():
        raise ValueError('Delivery point ID has conflicting coordinates')
    points['network_link']=pd.NA;points['access_status']='not_supplied'
    if cfg.get('network_file'):
        links=network_links(cfg['network_file'],set(cfg['network_modes']),sites.crs)
        joined=gpd.sjoin_nearest(points,links,how='left',max_distance=cfg['network_max_distance_m'],distance_col='access_distance_m',lsuffix='point',rsuffix='net')
        # Deterministic link choice for ties is marked unverified, not asserted to be a true entrance.
        joined=joined.sort_values('network_link_net').groupby(level=0).first().reindex(points.index)
        points['network_link']=joined.network_link_net
        points['access_status']=points.network_link.notna().map({True:'nearest_eligible_link_unverified',False:'no_link_within_threshold'})
    return points


def delivery_counts(counts,access,providers):
    parts=[]
    fallback=access.loc[access.carrier.eq('*')].set_index('site_id')
    for carrier in providers:
        column='count_'+carrier
        columns=['site_id',column]+(['segment'] if 'segment' in counts else [])
        active=counts.loc[counts[column]>0,columns].rename(columns={column:'packages'})
        if 'segment' not in active: active['segment']='unknown'
        chosen=fallback.copy()
        override=access.loc[access.carrier.eq(carrier)].set_index('site_id')
        if len(override): chosen.update(override)
        active=active.join(chosen[['delivery_point_id','network_link','access_status']],on='site_id',validate='many_to_one')
        if active.delivery_point_id.isna().any(): raise ValueError('Missing delivery mapping')
        active['carrier']=carrier
        parts.append(active)
    if not parts: return pd.DataFrame()
    result=pd.concat(parts,ignore_index=True).groupby(['delivery_point_id','carrier','segment','network_link','access_status'],dropna=False).packages.sum().reset_index()
    if result.packages.sum()!=counts[['count_'+p for p in providers]].to_numpy().sum(): raise AssertionError('Delivery transfer lost packages')
    result['service_event_proxy']=(~result.duplicated(['delivery_point_id','carrier'])).astype(int)
    return result


def export_hagrid(destinations,access,postal,path):
    """Field-compatible GIS adapter; _tag is private, _type business in current Java."""
    import hashlib
    aliases={'DHL':'dhl','Hermes':'hermes','UPS':'ups','DPD':'dpd','GLS':'gls','FedEx/TNT':'fedex','Amazon':'amazon'}
    points=access[['delivery_point_id','geometry']].drop_duplicates('delivery_point_id').set_index('delivery_point_id')
    active=points.loc[points.index.isin(destinations.delivery_point_id)].reset_index()
    active=gpd.GeoDataFrame(active,geometry='geometry',crs=access.crs)
    joined=gpd.sjoin(active,postal[['plz','geometry']],how='left',predicate='intersects')
    n=joined.groupby('delivery_point_id').plz.count()
    unique=joined.loc[joined.delivery_point_id.map(n).eq(1)].set_index('delivery_point_id').plz
    active['postal_cod']=active.delivery_point_id.map(unique).fillna('')
    active['id']=active.delivery_point_id.map(lambda s:int(hashlib.sha256(s.encode()).hexdigest()[:15],16))
    if not active.id.is_unique: raise ValueError('GIS ID collision')
    for carrier,alias in aliases.items():
        for segment,suffix in [('private','tag'),('business','type')]:
            values=destinations.loc[destinations.carrier.eq(carrier)&destinations.segment.eq(segment)].groupby('delivery_point_id').packages.sum()
            active[(alias+'_'+suffix)[:10]]=active.delivery_point_id.map(values).fillna(0).astype('int64')
    columns=[(alias+'_'+suffix)[:10] for alias in aliases.values() for suffix in ['tag','type']]
    active['total']=active[columns].sum(axis=1)
    if active.total.sum()!=destinations.packages.sum(): raise AssertionError('GIS export segment balance failed')
    active=active[['geometry','id','postal_cod',*columns,'total']]
    active.to_file(path,driver='GPKG',layer='demand',index=False)
    return {'rows':len(active),'packages':int(active.total.sum()),'missing_postal_codes':int(active.postal_cod.eq('').sum())}
