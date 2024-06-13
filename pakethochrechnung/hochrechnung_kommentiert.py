import pygeos
import pandas as pd
import geopandas as gpd
import numpy as np
import random
import matsim
import fiona
from shapely import geometry
import os

# Dateipfade zu den Eingabedaten
dhl_strassen_file = 'pakethochrechnung/dhl/dhl2streets_2021.shp'
# matsim_network_shp_file = 'Network_Hannover_081121.shp'
# matsim_network_xml_file = 'multimodalNetwork.xml'
prognose_2010_file = 'pakethochrechnung/Verflechtungsprognose2030/ketten-2010.csv'
prognose_2030_file = 'pakethochrechnung/Verflechtungsprognose2030/ketten-2030.csv'
hermes_file = 'pakethochrechnung/hermes/Hermes_PLZ-Menge_2019-2021.csv'

# Marktanteile der verschiedenen Paketdienste
marktanteile = {
    'dhl': 0.40,
    'hermes': 0.14,
    'ups': 0.12 * 1.2,
    'amazon': 0.10,
    'dpd': 0.10 * 1.2,
    'gls': 0.08 * 1.2,
    'fedex': 0.06 * 1.2
}

def verteil_na_werte():
    """
    Verteilt fehlende DHL-Daten auf Straßenabschnitte basierend auf bestimmten Kriterien.
    """
    try:
        # Versuche, die Datei zu lesen
        dhl_strassen_gdf = gpd.read_file(dhl_strassen_file, encoding='UTF-8')
    except fiona.errors.DriverError as e:
        # Drucke den Pfad, den er zu öffnen versucht, wenn die Datei nicht gefunden wird
        print(f"Fehler beim Öffnen der Datei: {os.path.abspath(dhl_strassen_file)}")
        raise e

    # Zielwert für die Summe der DHL-Daten
    dhl_target = 5660
    # Berechnung der verbleibenden Menge, die noch zugewiesen werden muss
    left = dhl_target - dhl_strassen_gdf[(dhl_strassen_gdf['tagesschni'] > 0) & (dhl_strassen_gdf['plz']=='30855')]['tagesschni'].sum()

    # Konvertierung der Geometrie in ein passendes Koordinatensystem und Berechnung der Längen
    geoms = dhl_strassen_gdf.to_crs('EPSG:32632')[(dhl_strassen_gdf['tagesschni'].isnull()) & (dhl_strassen_gdf['plz']=='30855')]['geometry'].length
    pop = geoms.reset_index().values
    # Zufällige Verteilung der verbleibenden Menge auf die Straßenabschnitte
    dhl_choice = random.choices(pop[:,0], weights=pop[:,1], k=int(left))

    for o in pop[:,0]:
        dhl_strassen_gdf.at[o, 'tagesschni'] = dhl_choice.count(o)

    # Überprüfung, ob die Summe der zugewiesenen Werte dem Zielwert entspricht
    assert dhl_strassen_gdf[dhl_strassen_gdf['plz']=='30855']['tagesschni'].sum() == dhl_target, 'DHL sum differs from target'
    
    # Konvertierung der Daten in Integer und Speichern der Ergebnisse
    dhl_strassen_gdf['tagesschni'] = dhl_strassen_gdf['tagesschni'].astype('int')
    dhl_strassen_gdf['plz'] = dhl_strassen_gdf['plz'].astype('int')
    dhl_strassen_gdf.to_file(driver='ESRI Shapefile', filename='dhl2streets_2021_fix.shp')
    print(dhl_strassen_gdf.dtypes)

def verteile_vm(marktanteile):
    """
    Verteilt die Marktdaten der Paketdienste auf die Straßenabschnitte.
    """
    dhl_strassen_gdf = gpd.read_file(dhl_strassen_file, encoding='UTF-8')
    dhl_strassen_gdf = dhl_strassen_gdf.to_crs('EPSG:32632')
    dhl_strassen_gdf = dhl_strassen_gdf.rename(columns={'tagesschni': 'dhl_tag'})

    hermes_df = pd.read_csv(hermes_file, encoding='utf-8', sep=';', thousands='.')
    hermes_df['hermes_tag'] = round(( hermes_df['2020'] + (8/12)*(hermes_df['2021']-hermes_df['2020']) ) / 26)

    # Gruppierung der Daten nach Postleitzahlen und Berechnung der Gesamtmengen
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
        # Verteilung der Daten auf die Straßenabschnitte basierend auf Gewichten
        pop = dhl_strassen_gdf[dhl_strassen_gdf['plz']==plz]['dhl_tag'].reset_index().values

        hermes_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['hermes_tag']))
        ups_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['ups_tag']))
        amazon_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['amazon_tag']))
        dpd_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['dpd_tag']))
        gls_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['gls_tag']))
        fedex_choice = random.choices(pop[:,0], weights=pop[:,1]+1, k=int(row['fedex_tag']))

        for s in pop[:,0]:
            # Speicherung der Ergebnisse in einer Liste
            kep_distribution.append([dhl_strassen_gdf.name[s], plz, dhl_strassen_gdf.geometry[s], 
                dhl_strassen_gdf.dhl_tag[s], hermes_choice.count(s), ups_choice.count(s), amazon_choice.count(s), 
                dpd_choice.count(s), gls_choice.count(s), fedex_choice.count(s)])
    
    strassen_gdf = gpd.GeoDataFrame(kep_distribution, crs='EPSG:32632', geometry='geometry', 
        columns=['str_name', 'plz', 'geometry', 'dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag'])

    # Tests und Validierung der Summen
    kep_sum = strassen_gdf[['dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag']].sum()
    assert kep_sum['dhl_tag'] == vm_plz['dhl_tag'].sum(), 'DHL sum differs'
    assert kep_sum['hermes_tag'] == vm_plz['hermes_tag'].sum(), 'Hermes sum differs'
    assert kep_sum['ups_tag'] == vm_plz['ups_tag'].sum(), 'UPS sum differs'
    assert kep_sum['amazon_tag'] == vm_plz['amazon_tag'].sum(), 'Amazon sum differs'
    assert kep_sum['dpd_tag'] == vm_plz['dpd_tag'].sum(), 'DPD sum differs'
    assert kep_sum['gls_tag'] == vm_plz['gls_tag'].sum(), 'GLS sum differs'
    assert kep_sum['fedex_tag'] == vm_plz['fedex_tag'].sum(), 'FedEx sum differs'
    print(sum(kep_sum), 'verteilt von', berechne_verflechtungsprognose(), 'prognostiziert')
    assert sum(kep_sum) < berechne_verflechtungsprognose(), 'Distributed sum exceeds upper projection'
    print('Marktanteile:')
    for k in ('dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag'):
        print('\t', k, round(kep_sum[k]/sum(kep_sum)*100,1))

    return strassen_gdf

def berechne_verflechtungsprognose():
    """
    Berechnet die Verflechtungsprognose basierend auf historischen Daten.
    """
    df_2010 = pd.read_csv(prognose_2010_file, encoding='utf-8', sep=';')
    df_filter_2010 = df_2010[(df_2010['Zielzelle']==3241) & (df_2010['GütergruppeNL']==150) | (df_2010['Zielzelle']==3241) & (df_2010['GütergruppeHL']==150)]
    paket_gew = 0.0065  # Durchschnittliches Paketgewicht in Tonnen
    ladungsnetto = 0.9  # Orientierung an vollem 40 Fuß Container
    pakete_2010 = ( df_filter_2010['TonnenNL'].sum() + df_filter_2010[df_filter_2010['TonnenNL']==0]['TonnenHL'].sum() ) * ladungsnetto / paket_gew
    kepzunahme = 4280/2330  # Zunahme aus KEP-Studie 2021
    jahresverlaufskorrektur = 1.15  # Korrektur für Jahresverlauf
    return round(pakete_2010 * kepzunahme / jahresverlaufskorrektur / 51 / 6)

def verteile_punkte(strassen_gdf, m=50):
    """
    Teilt die Straßenabschnitte in gleichmäßige Punkte auf.
    """
    pts = []
    for idx, row in strassen_gdf.iterrows():
        parts = round(row['geometry'].length / m)
        if parts <= 1:
            pt = row['geometry'].interpolate(0.5, normalized=True)
            pts.append([idx, 0, row['plz'], pt, 
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
                intp = delta + i * m
                pt = row['geometry'].interpolate(intp)
                pts.append([idx, i, row['plz'], pt, 
                    dhl_choice.count(i), hermes_choice.count(i), ups_choice.count(i), amazon_choice.count(i), 
                    dpd_choice.count(i), gls_choice.count(i), fedex_choice.count(i)])
    
    pt_gdf = gpd.GeoDataFrame(pts, crs='EPSG:32632', geometry='geometry', 
        columns=['str_idx', 'str_part', 'plz', 'geometry', 'dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag'])  
    
    # Tests
    assert strassen_gdf['dhl_tag'].sum() == pt_gdf['dhl_tag'].sum(), 'DHL line-point sum differs'
    assert strassen_gdf['hermes_tag'].sum() == pt_gdf['hermes_tag'].sum(), 'Hermes line-point sum differs'
    assert strassen_gdf['ups_tag'].sum() == pt_gdf['ups_tag'].sum(), 'UPS line-point sum differs'
    assert strassen_gdf['amazon_tag'].sum() == pt_gdf['amazon_tag'].sum(), 'Amazon line-point sum differs'
    assert strassen_gdf['dpd_tag'].sum() == pt_gdf['dpd_tag'].sum(), 'DPD line-point sum differs'
    assert strassen_gdf['gls_tag'].sum() == pt_gdf['gls_tag'].sum(), 'GLS line-point sum differs'
    assert strassen_gdf['fedex_tag'].sum() == pt_gdf['fedex_tag'].sum(), 'FedEx line-point sum differs'
    
    return pt_gdf

def verknuepfe_punkte(pt_gdf):
    """
    Verknüpft die Punkte mit dem MATSim Netzwerk.
    """
    matsim_gdf = matsim.read_network(matsim_network_xml_file).as_geo()
    matsim_gdf = matsim_gdf[matsim_gdf['modes'].str.contains('car', regex=False, na=False)]
    matsim_gdf = matsim_gdf[matsim_gdf['geometry'].is_valid]

    tree = pygeos.STRtree(pygeos.from_shapely(matsim_gdf.geometry))
    
    def get_matsim_id(idx):
        return str(matsim_gdf.iloc[idx, 6].values[0])
    def get_nearest_link(geom):
        match = tree.nearest(pygeos.from_shapely(geom))
        return get_matsim_id(match[1])
    def get_nearest_links(geom):
        match = tree.nearest_all(pygeos.from_shapely(geom))[:,1]
        if len(match) > 1:
            match = random.choice(match)
        return get_matsim_id(match)

    pt_gdf['matsim_id'] = pt_gdf.apply(lambda row: get_nearest_link(row['geometry']), axis=1)
    return pt_gdf

def matsim_xml_to_shp():
    """
    Konvertiert das MATSim Netzwerk von XML in ein Shapefile.
    """
    matsim_gdf = matsim.read_network(matsim_network_xml_file).as_geo()
    matsim_gdf = matsim_gdf[matsim_gdf['modes'].str.contains('car', regex=False, na=False)]
    matsim_gdf = matsim_gdf[matsim_gdf['geometry'].is_valid]
    matsim_gdf.to_file(driver='ESRI Shapefile', filename='matsim_network_car.shp')

def aggregiere_vm_matsim(vmmatsim):
    """
    Aggregiert die VM-Daten nach MATSim IDs.
    """
    matsim_gdf = matsim.read_network(matsim_network_xml_file).as_geo()
    matsim_gdf = matsim_gdf[matsim_gdf['modes'].str.contains('car', regex=False, na=False)]
    matsim_gdf = matsim_gdf[matsim_gdf['geometry'].is_valid]

    gdf = vmmatsim.merge(matsim_gdf, left_on='matsim_id', right_on='link_id').set_axis(vmmatsim.index)
    gdf['geometry'] = gdf.geometry.centroid

    # Tests
    assert vmmatsim['dhl_tag'].sum() == gdf['dhl_tag'].sum(), 'DHL sum differs'
    assert vmmatsim['hermes_tag'].sum() == gdf['hermes_tag'].sum(), 'Hermes sum differs'
    assert vmmatsim['ups_tag'].sum() == gdf['ups_tag'].sum(), 'UPS sum differs'
    assert vmmatsim['amazon_tag'].sum() == gdf['amazon_tag'].sum(), 'Amazon sum differs'
    assert vmmatsim['dpd_tag'].sum() == gdf['dpd_tag'].sum(), 'DPD sum differs'
    assert vmmatsim['gls_tag'].sum() == gdf['gls_tag'].sum(), 'GLS sum differs'
    assert vmmatsim['fedex_tag'].sum() == gdf['fedex_tag'].sum(), 'FedEx sum differs'

    return gdf[['geometry', 'dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag']]

# Hier wird das gesamte Skript in der richtigen Reihenfolge ausgeführt
if __name__ == "__main__":
    # 1. Fehlende NaN-Werte verteilen
    verteil_na_werte()

    # 2. Verteilung der Marktdaten auf Straßenabschnitte
    strassen_gdf = verteile_vm(marktanteile)
    strassen_gdf.to_file(driver='ESRI Shapefile', filename='vm-hochrechnung_strassen.shp', encoding='utf-8')

    # 3. Straßenabschnitte in Punkte unterteilen
    pt_gdf = verteile_punkte(strassen_gdf)
    pt_gdf.to_file(driver='ESRI Shapefile', filename='vm-hochrechnung_punkte.shp', encoding='utf-8')

    # # 4. Punkte mit MATSim Netzwerk verknüpfen
    # pt_gdf = gpd.read_file('vm-hochrechnung_punkte.shp', encoding='UTF-8')
    # pt_gdf_new = verknuepfe_punkte(pt_gdf)
    # pt_gdf_new.to_file(driver='ESRI Shapefile', filename='hochrechnung_punkte_matched.shp')

    # # 5. Aggregierte VM-Daten nach MATSim IDs
    # pt_gdf_new = gpd.read_file('hochrechnung_punkte_matched.shp', encoding='UTF-8')
    # vmmatsim = pt_gdf_new.groupby('matsim_id')[['dhl_tag', 'hermes_tag', 'ups_tag', 'amazon_tag', 'dpd_tag', 'gls_tag', 'fedex_tag']].sum()
    # vmmatsim.to_csv('vm_matsim.csv', encoding='UTF-8')

    # # 6. MATSim Punkte VM-Daten aggregieren
    # pt_matsim_vm = aggregiere_vm_matsim(vmmatsim)
    # pt_matsim_vm.to_file(driver='ESRI Shapefile', filename='vm-hochrechnung_matsimpunkte.shp')
