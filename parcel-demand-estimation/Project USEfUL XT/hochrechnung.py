#from shapely.geometry import LineString, Point
#from shapely.strtree import STRtree
import pygeos
import pandas as pd
import geopandas as gpd
import numpy as np
import random
import matsim
from shapely import geometry

# data input paths
dhl_strassen_file = 'dhl/dhl2streets_2021.shp'
matsim_network_shp_file = 'Network_Hannover_081121.shp'
matsim_network_xml_file = 'multimodalNetwork.xml'
prognose_2010_file = 'Verflechtungsprognose2030/ketten-2010.csv'
prognose_2030_file = 'Verflechtungsprognose2030/ketten-2030.csv'
hermes_file = 'hermes/Hermes_PLZ-Menge_2019-2021.csv'


def pre_distribute_NaN():
    dhl_strassen_gdf = gpd.read_file(dhl_strassen_file, encoding='UTF-8')
    print(dhl_strassen_gdf.dtypes)

    dhl_target = 5660
    left = dhl_target - dhl_strassen_gdf[(dhl_strassen_gdf['tagesschni'] > 0) & (dhl_strassen_gdf['plz']=='30855')]['tagesschni'].sum()

    geoms = dhl_strassen_gdf.to_crs('EPSG:32632')[(dhl_strassen_gdf['tagesschni'].isnull()) & (dhl_strassen_gdf['plz']=='30855')]['geometry'].length
    pop = geoms.reset_index().values
    dhl_choice = random.choices(pop[:,0], weights=pop[:,1], k=int(left))

    for o in pop[:,0]:
        dhl_strassen_gdf.at[o, 'tagesschni'] = dhl_choice.count(o)

    assert dhl_strassen_gdf[dhl_strassen_gdf['plz']=='30855']['tagesschni'].sum() == dhl_target, 'DHL sum differs from target'
    
    dhl_strassen_gdf['tagesschni'] = dhl_strassen_gdf['tagesschni'].astype('int')
    dhl_strassen_gdf['plz'] = dhl_strassen_gdf['plz'].astype('int')
    dhl_strassen_gdf.to_file(driver='ESRI Shapefile', filename='dhl2streets_2021_fix.shp')
    print(dhl_strassen_gdf.dtypes)
    exit()


def distribute_vm(marktanteile):
    dhl_strassen_gdf = gpd.read_file(dhl_strassen_file, encoding='UTF-8')
    dhl_strassen_gdf = dhl_strassen_gdf.to_crs('EPSG:32632')
    dhl_strassen_gdf = dhl_strassen_gdf.rename(columns={'tagesschni': 'dhl_tag'})

    hermes_df = pd.read_csv(hermes_file, encoding='utf-8', sep=';', thousands='.')
    hermes_df['hermes_tag'] = round(( hermes_df['2020'] + (8/12)*(hermes_df['2021']-hermes_df['2020']) ) / 26)

    vm_plz = dhl_strassen_gdf.groupby('plz')['dhl_tag'].sum()
    vm_plz = vm_plz.to_frame().merge(right=hermes_df, left_index=True, right_on='PLZ')[['PLZ', 'dhl_tag', 'hermes_tag']].set_index('PLZ')
    vm_plz['gesamt_tag'] = (vm_plz['dhl_tag'] + vm_plz['hermes_tag']) * (1 / (marktanteile['dhl'] + marktanteile['hermes']))
    vm_plz['ups_tag'] = round(marktanteile['ups'] * vm_plz['gesamt_tag'])
    vm_plz['amazon_tag'] = round(marktanteile['amazon'] * vm_plz['gesamt_tag'])
    vm_plz['dpd_tag'] = round(marktanteile['dpd'] * vm_plz['gesamt_tag'])
    vm_plz['gls_tag'] = round(marktanteile['gls'] * vm_plz['gesamt_tag'])
    vm_plz['fedex_tag'] = round(marktanteile['fedex'] * vm_plz['gesamt_tag'])
    
    kep_distribution = []
    for plz, row in vm_plz.iterrows():
        # gewichtet nach straße_dhl/dhl_tag aus x_tag ziehen
        pop = dhl_strassen_gdf[dhl_strassen_gdf['plz']==plz]['dhl_tag'].reset_index().values

        hermes_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['hermes_tag']))
        ups_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['ups_tag']))
        amazon_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['amazon_tag']))
        dpd_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['dpd_tag']))
        gls_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['gls_tag']))
        fedex_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['fedex_tag']))

        for s in pop[:,0]:
            # strasse, plz, geom, dhl, hermes, ups, amazon, dpd, gls, fedex
            kep_distribution.append([dhl_strassen_gdf.name[s], plz, dhl_strassen_gdf.geometry[s], 
                dhl_strassen_gdf.dhl_tag[s], hermes_choice.count(s), ups_choice.count(s), amazon_choice.count(s), 
                dpd_choice.count(s), gls_choice.count(s), fedex_choice.count(s)])
    
    strassen_gdf = gpd.GeoDataFrame(kep_distribution, crs='EPSG:32632', geometry='geometry', 
        columns=['str_name', 'plz', 'geometry', 'dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag'])

    # tests and validation
    kep_sum = strassen_gdf[['dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag']].sum()
    assert kep_sum['dhl_tag'] == vm_plz['dhl_tag'].sum(), 'DHL sum differs'
    assert kep_sum['hermes_tag'] == vm_plz['hermes_tag'].sum(), 'Hermes sum differs'
    assert kep_sum['ups_tag'] == vm_plz['ups_tag'].sum(), 'UPS sum differs'
    assert kep_sum['amazon_tag'] == vm_plz['amazon_tag'].sum(), 'Amazon sum differs'
    assert kep_sum['dpd_tag'] == vm_plz['dpd_tag'].sum(), 'DPD sum differs'
    assert kep_sum['gls_tag'] == vm_plz['gls_tag'].sum(), 'GLS sum differs'
    assert kep_sum['fedex_tag'] == vm_plz['fedex_tag'].sum(), 'FedEx sum differs'
    print(sum(kep_sum), 'verteilt von', verflechtungsprognose(), 'prognostiziert')
    assert sum(kep_sum) < verflechtungsprognose(), 'Distributed sum exceeds upper projection'
    print('markanteile')
    for k in ('dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag'):
        print('\t', k, round(kep_sum[k]/sum(kep_sum)*100,1))

    return strassen_gdf


def sample_streets(strassen_gdf, m=50):
    pts = []
    for idx, row in strassen_gdf.iterrows():
        parts = round(row['geometry'].length/m)
        if parts <= 1:
            i = 0
            pt = row['geometry'].interpolate(0.5, normalized=True)

            # idx, i, plz, geom, dhl, hermes, ups, amazon, dpd, gls, fedex
            pts.append([idx, i, row['plz'], pt, 
                row['dhl_tag'], row['hermes_tag'], row['ups_tag'], row['amazon_tag'], row['dpd_tag'], row['gls_tag'], row['fedex_tag']])
        else:
            delta = row['geometry'].length % m / 2
            dhl_choice = random.choices(range(parts), k=row['dhl_tag'])
            hermes_choice = random.choices(range(parts), k=row['hermes_tag'])
            ups_choice = random.choices(range(parts), k=row['ups_tag'])
            amazon_choice = random.choices(range(parts), k=row['amazon_tag'])
            dpd_choice = random.choices(range(parts), k=row['dpd_tag'])
            gls_choice = random.choices(range(parts), k=row['gls_tag'])
            fedex_choice = random.choices(range(parts), k=row['fedex_tag'])
            for i in range(parts):
                intp = delta + i*m
                pt = row['geometry'].interpolate(intp)
                
                # idx, i, plz, geom, dhl, hermes, ups, amazon, dpd, gls, fedex
                pts.append([idx, i, row['plz'], pt, 
                    dhl_choice.count(i), hermes_choice.count(i), ups_choice.count(i), amazon_choice.count(i), 
                    dpd_choice.count(i), gls_choice.count(i), fedex_choice.count(i)])
    
    pt_gdf = gpd.GeoDataFrame(pts, crs='EPSG:32632', geometry='geometry', 
        columns=['str_idx', 'str_part', 'plz', 'geometry', 'dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag'])  
    
    # tests
    assert strassen_gdf['dhl_tag'].sum() == pt_gdf['dhl_tag'].sum(), 'DHL line-point sum differs'
    assert strassen_gdf['hermes_tag'].sum() == pt_gdf['hermes_tag'].sum(), 'Hermes line-point sum differs'
    assert strassen_gdf['ups_tag'].sum() == pt_gdf['ups_tag'].sum(), 'UPS line-point sum differs'
    assert strassen_gdf['amazon_tag'].sum() == pt_gdf['amazon_tag'].sum(), 'Amazon line-point sum differs'
    assert strassen_gdf['dpd_tag'].sum() == pt_gdf['dpd_tag'].sum(), 'DPD line-point sum differs'
    assert strassen_gdf['gls_tag'].sum() == pt_gdf['gls_tag'].sum(), 'GLS line-point sum differs'
    assert strassen_gdf['fedex_tag'].sum() == pt_gdf['fedex_tag'].sum(), 'FedEx line-point sum differs'
    
    return pt_gdf


def verflechtungsprognose():
    df_2010 = pd.read_csv(prognose_2010_file, encoding='utf-8', sep=';')
    df_filter_2010 = df_2010[(df_2010['Zielzelle']==3241) & (df_2010['GütergruppeNL']==150) | (df_2010['Zielzelle']==3241) & (df_2010['GütergruppeHL']==150)]
    #print(df_filter_2010[['Quellzelle', 'QuellzelleHL', 'ZielzelleHL', 'ModeVL', 'ModeHL', 'ModeNL', 'GütergruppeVL', 'GütergruppeHL', 'GütergruppeNL', 'TonnenVL', 'TonnenHL', 'TonnenNL']])
    paket_gew = 0.0065 # durchschnittliches paketgewicht in t
    ladungsnetto = 0.9 # orientiert an vollem 40 fuß container
    pakete_2010 = ( df_filter_2010['TonnenNL'].sum() + df_filter_2010[df_filter_2010['TonnenNL']==0]['TonnenHL'].sum() ) * ladungsnetto / paket_gew
    #print('Verflechtungsbasis 2010', round(pakete_2010/1000000, 1), 'Mio.')
    kepzunahme = 4280/2330 # aus abb 4 in BIEK KEP-Studie 2021
    #print('Verflechtung+Trend 2020', round(pakete_2010/1000000*kepzunahme, 1), 'Mio.')

    #hochrechnungAusDhlHermes_woche = 1102114 # bei dhl+hermes=50%
    jahresverlaufskorrektur = 1.15 # 1. jahreshälfte liegt üblicherweise unter jahresschnitt
    #print('Hochrechnung Daten 2021', round(hochrechnungAusDhlHermes_woche*52*jahresverlaufskorrektur/1000000, 1), 'Mio.')

    #df_2030 = pd.read_csv(prognose_2030_file, encoding='utf-8', sep=';')
    #df_filter_2030 = df_2030[(df_2030['Zielzelle']==3241) & (df_2030['GütergruppeNL']==150) | (df_2030['Zielzelle']==3241) & (df_2030['GütergruppeHL']==150)]
    #print(df_filter_2030[['Quellzelle', 'QuellzelleHL', 'ZielzelleHL', 'ModeVL', 'ModeHL', 'ModeNL', 'GütergruppeVL', 'GütergruppeHL', 'GütergruppeNL', 'TonnenVL', 'TonnenHL', 'TonnenNL']])
    #pakete_nl = df_filter_2030['TonnenNL'].sum()*1000/7.4
    #pakete_hl = df_filter_2030[df_filter_2030['TonnenNL']==0]['TonnenHL'].sum()*1000/7.4
    #print('Verflechtung 2030', round((pakete_nl+pakete_hl)/1000000,1), 'Mio.')

    return round(pakete_2010*kepzunahme/jahresverlaufskorrektur/51/6)


def match_points(pt_gdf):
    #pt_gdf = pt_gdf[:10]
    #matsim_gdf = gpd.read_file(matsim_network_shp_file, encoding='UTF-8') #shp version
    matsim_gdf = matsim.read_network(matsim_network_xml_file).as_geo() #xml version
    matsim_gdf = matsim_gdf[matsim_gdf['modes'].str.contains('car', regex=False, na=False)] #xml version
    matsim_gdf = matsim_gdf[matsim_gdf['geometry'].is_valid]

    tree = pygeos.STRtree(pygeos.from_shapely(matsim_gdf.geometry))
    
    def get_matsim_id(idx):
        #return str(matsim_gdf.iloc[idx, 0].values[0]) #shp version
        return str(matsim_gdf.iloc[idx, 6].values[0]) #xml version
    def get_nearest_link(geom):
        match = tree.nearest( pygeos.from_shapely(geom) )
        return get_matsim_id(match[1])
    def get_nearest_links(geom):
        match = tree.nearest_all( pygeos.from_shapely(geom) )[:,1]
        if len(match) > 1:
            match = random.choice(match)
        return get_matsim_id(match)

    pt_gdf['matsim_id'] = pt_gdf.apply(lambda row: get_nearest_link(row['geometry']), axis=1)
    return pt_gdf


def matsim_xml2shp():
    matsim_gdf = matsim.read_network(matsim_network_xml_file).as_geo() #xml version
    matsim_gdf = matsim_gdf[matsim_gdf['modes'].str.contains('car', regex=False, na=False)] #xml version
    matsim_gdf = matsim_gdf[matsim_gdf['geometry'].is_valid]
    matsim_gdf.to_file(driver='ESRI Shapefile', filename='D:/Hannover Daten/MatSim/Network XT/from_multimodalNetwork.xml_only_car.shp')


def matsim2pt_vm(vmmatsim):
    matsim_gdf = matsim.read_network(matsim_network_xml_file).as_geo() #xml version
    matsim_gdf = matsim_gdf[matsim_gdf['modes'].str.contains('car', regex=False, na=False)] #xml version
    matsim_gdf = matsim_gdf[matsim_gdf['geometry'].is_valid]

    gdf = vmmatsim.merge(matsim_gdf, left_on='matsim_id', right_on='link_id').set_axis(vmmatsim.index)
    gdf['geometry'] = gdf.geometry.centroid

    # tests
    assert vmmatsim['dhl_tag'].sum() == gdf['dhl_tag'].sum(), 'DHL sum differs'
    assert vmmatsim['hermes_tag'].sum() == gdf['hermes_tag'].sum(), 'Hermes sum differs'
    assert vmmatsim['ups_tag'].sum() == gdf['ups_tag'].sum(), 'UPS sum differs'
    assert vmmatsim['amazon_tag'].sum() == gdf['amazon_tag'].sum(), 'Amazon sum differs'
    assert vmmatsim['dpd_tag'].sum() == gdf['dpd_tag'].sum(), 'DPD sum differs'
    assert vmmatsim['gls_tag'].sum() == gdf['gls_tag'].sum(), 'GLS sum differs'
    assert vmmatsim['fedex_tag'].sum() == gdf['fedex_tag'].sum(), 'FedEx sum differs'

    return gdf[['geometry', 'dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag']]


marktanteile = {
        'dhl': 0.40,
        'hermes': 0.14,
        'ups': 0.12*1.2,
        'amazon': 0.10,
        'dpd': 0.10*1.2,
        'gls': 0.08*1.2,
        'fedex': 0.06*1.2
    }


#pre_distribute_NaN()

#line_gdf = distribute_vm(marktanteile)
#line_gdf.to_file(driver='ESRI Shapefile', filename='hochrechnung/vm-hochrechnung_strassen.shp', encoding='utf-8')
#pt_gdf = sample_streets(line_gdf)
#pt_gdf.to_file(driver='ESRI Shapefile', filename='hochrechnung/vm-hochrechnung_punkte.shp', encoding='utf-8')

#pt_gdf = gpd.read_file('D:/Hannover Daten/Paketdienstleister/DHL/2021/hochrechnung/vm-hochrechnung_punkte_25832.shp', encoding='UTF-8')
#pt_gdf_new = match_points(pt_gdf)
#pt_gdf_new.to_file(driver='ESRI Shapefile', filename='D:/Hannover Daten/Paketdienstleister/DHL/2021/hochrechnung/hochrechnung_punkte_25832_matched.shp')
pt_gdf_new = gpd.read_file('D:/Hannover Daten/Paketdienstleister/DHL/2021/hochrechnung/hochrechnung_punkte_25832_matched.shp', encoding='UTF-8')
vmmatsim = pt_gdf_new.groupby('matsim_id')[['dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag']].sum()
#vmmatsim.to_csv('D:/Hannover Daten/Paketdienstleister/DHL/2021/hochrechnung/vm_matsim.csv', encoding='UTF-8')

pt_matsim_vm = matsim2pt_vm(vmmatsim)
pt_matsim_vm.to_file(driver='ESRI Shapefile', filename='D:/Hannover Daten/Paketdienstleister/DHL/2021/hochrechnung/vm-hochrechnung_matsimpunkte_25832.shp')