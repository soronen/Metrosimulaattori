package application.controller;

import application.MainApp;
import application.eduni.distributions.Normal;
import application.simu.framework.IMoottori;
import application.simu.framework.Kello;
import application.simu.framework.Tapahtuma;
import application.simu.model.OmaMoottori;
import application.simu.model.Palvelupiste;
import application.simu.model.TapahtumanTyyppi;
import application.view.IVisualisointi;
import application.view.graphviewcontroller;
import dao.ISimulaattoriDAO;
import dao.SimulaattoriDAO;
import entity.ServicePoint;
import entity.Simulaattori;
import entity.Station;
import javafx.application.Platform;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.ListView;
import java.util.List;


/**
 * MVC-mallin mukainen controller, jota käyttöliittymä käyttää mallin kanssa kommunikointiin.
 *
 * @author Eetu Soronen, Emil Ålgars
 * @version 1
 */

public class Kontrolleri implements IKontrolleri {

    /**
     * Onko kontrollerin ohjaama simulaattori tällä hetkellä käynnissä?
     */

    boolean kaynnissa = false;
    /**
     * Moottori, joka sisältää simulaation logiikan
     */

    private IMoottori moottori;
    /**
     * Käyttöliittymä, joka sisältää simulaation graafisen käyttöliittymän
     */

    private IVisualisointi ui;
    /**
     * Kauan simulaattoria ajetaan
     */
    private int simukesto = 1000;

    /**
     * Metron maksimikapasiteetti
     */
    private int metronKapasiteetti = 40;

    /**
     * Aseman maksimikapasiteetti
     */
    private int asemanKapasiteetti = 200;

    /**
     * Taulukko simulaattorin palvelupisteistä. (entrance, ticketsales, ticketcheck, metro)
     */
    private Palvelupiste[] palvelupisteet;


    /**
     * Saapuminen-palvelupisteen käsittelyajan odotusarvo
     */
    private int entranceMean = 4;

    /**
     * Saapuminen-palvelupisteen käsittelyajan odotusarvo
     */
    private int entranceVariance = 8;

    /**
     * Lipunmyynti-palvelupisteen käsittelyajan odotusarvo
     */
    private int salesMean = 20;

    /**
     * Lipunmyynti-palvelupisteen käsittelyajan varianssi
     */
    private int salesVariance = 10;

    /**
     * lipuntarkastus-palvelupisteen käsittelyajan odotusarvo
     */
    private int checkMean = 7;

    /**
     * lipuntarkastus-palvelupisteen käsittelyajan varianssi
     */
    private int checkVariance = 3;

    /**
     * metro-palvelupisteen käsittelyajan odotusarvo
     */
    private int metroMean = 360;

    /**
     * metro-palvelupisteen käsittelyajan varianssi
     */
    private int metroVariance = 60;

    /**
     * Simulaattorin saapumisgeneraattorin normaalijakauman odotusarvo
     */
    private int arrivalMean = 10;

    /**
     * Simulaattorin saapumisgeneraattorin normaalijakauman varianssi
     */
    private int arrivalVariance = 5;

    /**
     * Onko simulaattori pysäytetty? Miten tämä eroaa {@link #kaynnissa} muuttujasta? En tiedä!
     */
    private boolean simuStopped = false;

    /**
     * Konstruktori joka kutsuu Mainapin {@link application.MainApp setKontrol(this)} metodia.
     */
    public Kontrolleri() {
        MainApp.setKontrol(this);
    }


    /**
     * Hakee moottorin, asettaa sen parametrit, asettaa simulaattorin kellon nollaan ja käynnistää simulaation.
     * Jos simu on jo käynnissä, luo virheilmoitusponnahdusikkunan.
     */
    @Override
    public void kaynnistaSimulointi() {
        if (((Thread) moottori).getState() != Thread.State.NEW) {

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Error");
            alert.setHeaderText("Uutta moottoria ei luotu!");
            alert.setContentText("Muistitko nollata moottorin?");
            alert.show();
            return;
        }
        moottori = getMoottori();
        Kello.getInstance().setAika(0);
        asetaMoottorinParametrit();
        if (!kaynnissa && ((Thread) moottori).getState() == Thread.State.NEW) {
            kaynnissa = true;
            ((Thread) moottori).start();
        }
    }

    /**
     * Asettaa moottorin parametrit. Metron ja aseman kapasiteetit sekä saapumisen ja palvelupisteiden käsittelyajan odotusarvot ja varianssit.
     */
    @Override
    public void asetaMoottorinParametrit() {
        setMetronKapasiteetti(metronKapasiteetti);
        setAsemanKapasiteetti(asemanKapasiteetti);

        setEntranceJakauma(entranceMean, entranceVariance);
        setSalesJakauma(salesMean, salesVariance);
        setCheckJakauma(checkMean, checkVariance);
        setMetroJakauma(metroMean, metroVariance);


    }

    /**
     * Kutsuu setKaynnissa(false) -metodia.
     * Jos moottori != null asettaa simlointiajan nollaksi ja poistaa simulaattorin.
     */
    @Override
    public void resetSimulator() {
        setKaynnissa(false);
        if (moottori != null) {
            moottori.setSimulointiaika(0);
            moottori = null;
            getMoottori();
        }

    }

    /**
     * pysäyttää!!1! ⛔🚫🛑🚫  simulaattorin kesken asetmmalla simulointiajan nollaan.
     * asettaa moottorin null arvoksi pienen viiveen kuluttua tästä, kun muutkin säikeet ovat saaneet kuulla uutiset
     * jotta konsoliin ei tulisi virheilmoituksia
     */
    @Override
    public void stopSimulation() {
        setKaynnissa(false);
        simuStopped = true;
        moottori.setSimulointiaika(0);
        Platform.runLater(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(100);
                    moottori = null;
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    /**
     * Palauttaa moottorin. Jos moottori = null, luo sen.
     *
     * @return OmaMoottori-olio, jonka saapmuisen jakauma on asetettu (arrivalMean ja arrivalVariance);
     */
    @Override
    public IMoottori getMoottori() {
        if (moottori == null) {
            moottori = new OmaMoottori(this, arrivalMean, arrivalVariance); // luodaan uusi moottorisäie jokaista simulointia varten
            palvelupisteet = moottori.getPalvelupisteet();
        }
        return moottori;
    }

    /**
     * Palauttaa moottorin palvelupisteet
     *
     * @return Palvelupiste[]-taulukko, joka sisältää moottorin palvelupisteet. [0] = Entrance, [1] = Sales, [2] = Check, [3] = Metro
     */
    @Override
    public Palvelupiste[] getPalvelupisteet() {
        return moottori.getPalvelupisteet();
    }

    /**
     * Nopeuttaa simulaattoria laskemalla simulaattorin viivettä 10%:lla.
     */
    @Override
    public void nopeuta() {
        moottori.setViive((long) (moottori.getViive() * 0.9));
    }

    /**
     * Korottaa mottorin viivettä 10%:lla ja lisää tulokseen vielä ykkösen.
     * Ykkönen lisätään, jotta viive nousee nollastakin.
     */
    @Override
    public void hidasta() {
        moottori.setViive((long) (moottori.getViive() * 1.10 + 1));
    }

    /**
     * OmaMoottorin kutsuma metodi, joka kutsuu UI:n päivitäUI()-metodia jokaisen tapahtuman käsittelyn yhteydessä.
     * Vastaa käyttöliittymän päivittämisestä.
     *
     * @param t Tapahtuma-olio, joka sisältää tiedon tapahtuneesta tapahtumasta.
     */
    @Override
    public void paivitaUI(Tapahtuma t) {
        ui.paivitaUI(t);
    }

    /**
     * Asettaa simulaattorin keston moottorissa ja tallentaa sen kontrollerin simukesto-muuttujaan.
     *
     * @param simukesto asetetaan simulaattorin kestoksi
     */
    @Override
    public void setsimulaattorinKesto(int simukesto) {
        this.simukesto = simukesto;
        moottori.setSimulointiaika(simukesto);
    }

    /**
     * Asettaa simulaattorin viiveen parametrina annettuun arvoon.
     *
     * @param simuviive long arvo, joka asetetaan simulaattorin viiveeksi.
     */
    @Override
    public void setSimulaattorinViive(int simuviive) {
        moottori.setViive(simuviive);

    }

    /**
     * Palauttaa metro-palvelupisteen kapasiteetin.
     *
     * @return Metro-palvelupisteen kapasiteetti int-arvona.
     */
    @Override
    public int getMetronKapasiteetti() {
        return moottori.getMetroCapacity();
    }

    /**
     * Asettaa metro-palvelupisteen kapasiteetin parametrina annettuun arvoon.
     *
     * @param metronKapasiteetti int-arvo, joka asetetaan metro-palvelupisteen kapasiteetiksi.
     */
    @Override
    public void setMetronKapasiteetti(int metronKapasiteetti) {
        this.metronKapasiteetti = metronKapasiteetti;
        moottori.setMetroCapacity(metronKapasiteetti);
    }

    /**
     * Palauttaa aseman-kapasiteetin.
     * Jos asemassa on tätä lukua enemmän asiakkaita, entrance-palvelupiste ei vastaanota asiakkaita.
     *
     * @return aseman kapasiteetti (int)
     */
    @Override
    public int getAsemanKapasiteetti() {
        return moottori.getStationCapacity();
    }

    /**
     * Asettaa aseman kapasiteetin parametrina annettuun arvoon.
     * Jos asemassa on tätä lukua enemmän asiakkaita, entrance-palvelupiste ei vastaanota asiakkaita.
     *
     * @param asemanKapasiteetti aseman kapasiteetti (int)
     */
    @Override
    public void setAsemanKapasiteetti(int asemanKapasiteetti) {
        this.asemanKapasiteetti = asemanKapasiteetti;
        moottori.setStationCapacity(asemanKapasiteetti);
    }

    /**
     * Palauttaa asemassa olevat asiakkaat (entrance-palvelupisteen läpi menneet asiakkaat - metro-palvelupisteen käsittelemät asiakkaat)
     *
     * @return asemassa olevien asiakkaiden lukumäärä (int)
     */
    @Override
    public int getAsiakkaatAsemassa() {
        return moottori.getCustomersWithin();
    }

    /**
     * Palauttaa metro-palvelupisteen käsittelemät asiakkaat.
     *
     * @return palvellut asiakkaat (int)
     */
    @Override
    public int getPalvellutAsaiakkaat() {
        return moottori.getServedCustomers();
    }

    /**
     * Palauttaa simulaattorin tämänhetkisen viiveen
     * @return simulaattorin viive (long)
     */
    @Override
    public long getViive() {
        return moottori.getViive();
    }

    /**
     * setteri
     * @param kaynnissa {@link #kaynnissa}
     */
    @Override
    public void setKaynnissa(boolean kaynnissa) {
        this.kaynnissa = kaynnissa;
    }

    /**
     * getteri (huonosti nimetty)
     * @return kaynnissa {@link #kaynnissa}
     */
    @Override
    public boolean onkoKaynnissa() {
        return kaynnissa;
    }

    /**
     * getteri. Prosenttiarvo, kuinka monta asiakasta hyppää lipunmyynnin ohi.
     * @return % asiakkaista, jotka hyppäävät lipunmyynnin ohi.
     */
    @Override
    public int getMobiililippujakauma() {
        return moottori.getMobiililippujakauma();
    }

    /**
     * setteri. Prosenttiarvo, kuinka monta asiakasta hyppää lipunmyynnin ohi.
     * @param % asiakkaista, jotka hyppäävät lipunmyynnin ohi.
     */
    @Override
    public void setMobiililippujakauma(int mobiililippujakauma) {
        moottori.setMobiililippujakauma(mobiililippujakauma);
    }

    /**
     * Asettaa sisääkäynti-palvelupisteen käsittelyajan normaalijakauman odotusarvon ja varianssin.
     * @param mean Odotusarvo
     * @param variance Varianssi
     */
    @Override
    public void setEntranceJakauma(int mean, int variance) {
        entranceMean = mean;
        entranceVariance = variance;
        palvelupisteet[0].setJakauma(new Normal(entranceMean, entranceVariance));
    }

    /**
     * Asettaa lipunmyynti-palvelupisteen käsittelyajan normaalijakauman odotusarvon ja varianssin.
     * @param mean Odotusarvo
     * @param variance Varianssi
     */
    @Override
    public void setSalesJakauma(int mean, int variance) {
        salesMean = mean;
        salesVariance = variance;

        palvelupisteet[1].setJakauma(new Normal(salesMean, salesVariance));
    }

    /**
     * Asettaa lipuntarkastus-palvelupisteen käsittelyajan normaalijakauman odotusarvon ja varianssin.
     * @param mean Odotusarvo
     * @param variance Varianssi
     */
    @Override
    public void setCheckJakauma(int mean, int variance) {
        checkMean = mean;
        checkVariance = variance;
        palvelupisteet[2].setJakauma(new Normal(checkMean, checkVariance));
    }

    /**
     * Asettaa metro-palvelupisteen käsittelyajan normaalijakauman odotusarvon ja varianssin.
     * @param mean Odotusarvo
     * @param variance Varianssi
     */
    @Override
    public void setMetroJakauma(int mean, int variance) {
        metroMean = mean;
        metroVariance = variance;
        palvelupisteet[3].setJakauma(new Normal(metroMean, metroVariance));
    }


    /**
     * Arvot tallennetaan kontrolleriin ja niitä käytetään, kun luodaan uusi moottori.
     * OmaMoottorin konstruktori käyttää näitä luodakseen saapumisgeneraattorin (joka luo uusia asiakkaita normaalijakauman mukaisesti)
      * @param mean Normaalijakauman odotusarvo
     * @param variance Normaalijakauman varianssi
     */
    @Override
    public void setArrivalJakauma(int mean, int variance) {
        arrivalMean = mean;
        arrivalVariance = variance;
    }


    /**
     * Tallentaa kontrolleriin palvelupisteidne käsittelyajan normaalijakauman odotusarvon ja varianssin.
     * Jakaumat asetataan simulaattorille käynnistyksen yhteydessä.
     * @param tt Tapahtumaa vastaava palvelupiste. (entrance, ticketsales, ticketcheck, metro)
     * @param mean Normaalijakauman odotusarvo
     * @param variance Normaalijakauman varianssi
     */
    @Override
    public void setPPJakauma(TapahtumanTyyppi tt, int mean, int variance) {
        switch (tt) {
            case ENTRANCE:
                entranceMean = mean;
                entranceVariance = variance;
                break;
            case TICKETSALES:
                salesMean = mean;
                salesVariance = variance;
                break;
            case TICKETCHECK:
                checkMean = mean;
                checkVariance = variance;
                break;
            case METRO:
                metroMean = mean;
                metroVariance = variance;
                break;
        }
    }

    /**
     * Palauttaa palvelupisteen tämänhetkisen tilan, eli käsitteleekö se asiakkaita juuri nyt?
     * @param palvelupiste Tapahtumaa vastaava palvelupiste. (entrance, ticketsales, ticketcheck, metro)
     * @return onko palvelupiste varattu? true / false
     */
    @Override
    public boolean onkoPPVarattu(TapahtumanTyyppi palvelupiste) {
        switch (palvelupiste) {
            case ENTRANCE:
                return palvelupisteet[0].onVarattu();
            case TICKETSALES:
                return palvelupisteet[1].onVarattu();
            case TICKETCHECK:
                return palvelupisteet[2].onVarattu();
            case METRO:
                return palvelupisteet[3].onVarattu();
        }
        return false;
    }

    /**
     * Palauttaa palvelupisteen jonon pituuden tällä hetkellä.
     * @param palvelupiste Tapahtumaa vastaava palvelupiste. (entrance, ticketsales, ticketcheck, metro)
     * @return
     */
    @Override
    public int getPPjononpituus(TapahtumanTyyppi palvelupiste) {
        int index = 0;
        switch (palvelupiste) {
            case ENTRANCE:
                index = 0;
                break;
            case TICKETSALES:
                index = 1;
                break;
            case TICKETCHECK:
                index = 2;
                break;
            case METRO:
                index = 3;
                break;
        }
        return palvelupisteet[index].getJonopituus();
    }

    /**
     * Palauttaa keskimääräisen jononkeston palvelupisteessä.
     * @param palvelupiste Tapahtumaa vastaava palvelupiste. (entrance, ticketsales, ticketcheck, metro)
     * @return
     */
    @Override
    public double getPPkeskijonoaika(TapahtumanTyyppi palvelupiste) {
        int index = 0;
        switch (palvelupiste) {
            case ENTRANCE:
                index = 0;
                break;
            case TICKETSALES:
                index = 1;
                break;
            case TICKETCHECK:
                index = 2;
                break;
            case METRO:
                index = 3;
                break;
        }
        return palvelupisteet[index].getKeskijonoaika();
    }

    /**
     * Palauttaa palvelupisteen käsittelemien asiakkaiden lukumäärän.
     * @param palvelupiste Tapahtumaa vastaava palvelupiste. (entrance, ticketsales, ticketcheck, metro)
     * @return
     */
    @Override
    public int getPPpalvellutAsiakkaat(TapahtumanTyyppi palvelupiste) {
        int index = 0;
        switch (palvelupiste) {
            case ENTRANCE:
                // entrance-pisteen palvellut asiakkaat ei ole oikein, jonka takia pitää tehdä näin..
                // johtuu varmaan siitä, että jos asiakas on täynnä, se lasketaan palvelluksi vaikka todellisuudessa se vain poistuu systeemistä.
                return getAsiakkaatAsemassa()+getPalvellutAsaiakkaat();
            case TICKETSALES:
                index = 1;
                break;
            case TICKETCHECK:
                index = 2;
                break;
            case METRO:
                index = 3;
                System.out.println("metron palvelunro = " + palvelupisteet[3].getPalvelunro());
                break;
        }
        return palvelupisteet[index].getPalvelunro();
    }

    /**
     * Palauttaa palvelupisteen käsittelemien asiakkaiden keskimääräisen käsittelyajan. (palvelun keston keskiaika)
     * @param palvelupiste Tapahtumaa vastaava palvelupiste. (entrance, ticketsales, ticketcheck, metro)
     * @return
     */
    @Override
    public double getPPkeskiarvoaika(TapahtumanTyyppi palvelupiste) {
        int index = 0;
        switch (palvelupiste) {
            case ENTRANCE:
                index = 0;
                break;
            case TICKETSALES:
                index = 1;
                break;
            case TICKETCHECK:
                index = 2;
                break;
            case METRO:
                index = 3;
                break;
        }
        return palvelupisteet[index].getKeskiarvoaika();
    }


    /**
     * Palauttaa palvelupisteen generaattorin odotus ja varianssiarvot
     *
     * @param tt TapahtumanTyyppi, joka vastaa palvelupistettä
     * @return int[2] taulukon, jossa i[0] = odotusarvo ja i[1] = varianssi
     */
    @Override
    public int[] getPPJakauma(TapahtumanTyyppi tt) {
        switch (tt) {
            case ENTRANCE:
                return new int[]{entranceMean, entranceVariance};
            case TICKETSALES:
                return new int[]{salesMean, salesVariance};
            case TICKETCHECK:
                return new int[]{checkMean, checkVariance};
            case METRO:
                return new int[]{metroMean, metroVariance};
        }
        return null;
    }


    @Override
    public void setUi(IVisualisointi iv) {

        System.out.println(iv + " vaihdettu");

        this.ui = iv;
    }


    /**
     * Tallentaa simulaattorin asetukset ja loppuarvot ensiksi olioksi ja sitten tietokantaan.
     * Kuitenkin jos simulaattori on keskeytetty ennenaikaisesti, ei tee mitään.
     *
     * @param mtr OmaMoottori-olio, jonka parametrit ja palvelupisteet tallennetaan.
     */
    @Override
    public void tallenaEntity(OmaMoottori mtr) {
        if (simuStopped == true) {
            simuStopped = false;
            return;
        }

        Palvelupiste[] ppt = mtr.getPalvelupisteet();
        ServicePoint[] spoints = new ServicePoint[4];
        int palvellutAsiakkaat = 0;

        Station station = new Station(
                getMobiililippujakauma(), arrivalMean, arrivalVariance,
                mtr.getCustomersWithin(), mtr.getServedCustomers(),
                mtr.getStationCapacity());


        // luo ServicePoint-olion jokaisesta palvelupisteestä
        for (int i = 0; i < 4; i++) {
            TapahtumanTyyppi t = TapahtumanTyyppi.ENTRANCE;

            switch (i) {
                case 0:
                    t = TapahtumanTyyppi.ENTRANCE;
                    palvellutAsiakkaat = getPPpalvellutAsiakkaat (TapahtumanTyyppi.ENTRANCE);
                    break;
                case 1:
                    t = TapahtumanTyyppi.TICKETSALES;
                    palvellutAsiakkaat = getPPpalvellutAsiakkaat (TapahtumanTyyppi.TICKETSALES);
                    break;
                case 2:
                    t = TapahtumanTyyppi.TICKETCHECK;
                    palvellutAsiakkaat = getPPpalvellutAsiakkaat (TapahtumanTyyppi.TICKETCHECK);
                    break;
                case 3:
                    t = TapahtumanTyyppi.METRO;
                    palvellutAsiakkaat = getPPpalvellutAsiakkaat (TapahtumanTyyppi.METRO);
                    break;
            }


            spoints[i] = new ServicePoint(
                    t,
                    palvellutAsiakkaat,
                    ppt[i].getJonopituus(),
                    ppt[i].getKeskiarvoaika(),

                    ppt[i].getKeskijonoaika(),
                    getPPJakauma(t)[0],
                    getPPJakauma(t)[1],
                    mtr.getMetroCapacity());
        }

        Simulaattori sim = new Simulaattori(simukesto, spoints[0], spoints[1], spoints[2], spoints[3], station);
        SimulaattoriDAO dao = new SimulaattoriDAO();
        dao.lisaaSimulaattori(sim);

    }


    @Override
    public void asetachart(graphviewcontroller i, int x) {

        //asetetaan chartin tiedot

        i.getbarChart().getData().clear();

        XYChart.Series<String, Double> series = new XYChart.Series<>();

        ListView lv = i.getListView();

        lv.getSelectionModel().getSelectedIndex();

        SimulaattoriDAO sdao = new SimulaattoriDAO();

        List<Simulaattori> lista = sdao.listaaSimulaattorit();

        int id = 1;
        if (!(i.getListView().getSelectionModel().getSelectedIndex() < 0)){
            String ids = (String) i.getListView().getItems().get(i.getListView().getSelectionModel().getSelectedIndex());
            try {
                id = Integer.parseInt(ids.replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            String ids = (String) i.getListView().getItems().get(0);
            try {
                id = Integer.parseInt(ids.replaceAll("[^0-9]", ""));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }



        Simulaattori sim = sdao.haeSimulaattori(id);



        if (lista == null || lista.size() == 0) {

            Alert a = new Alert(Alert.AlertType.NONE);
            a.setAlertType(Alert.AlertType.ERROR);
            a.setContentText("Sql tietokannassa ei simulaation tuloksia! D:");
            a.show();
            return;

        }

        ServicePoint sp = new ServicePoint();

        switch (x) {
            case 1:

                sp = sim.getEntrance();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getJononKeskikesto()));

                sp = sim.getMetro();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getJononKeskikesto()));

                sp = sim.getTicketcheck();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getJononKeskikesto()));

                sp = sim.getTicketsales();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getJononKeskikesto()));


                break;
            case 2:


                sp = sim.getEntrance();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), Double.valueOf(sp.getPalvellutAsiakkaat())));

                sp = sim.getMetro();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), Double.valueOf(sp.getPalvellutAsiakkaat())));

                sp = sim.getTicketcheck();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), Double.valueOf(sp.getPalvellutAsiakkaat())));

                sp = sim.getTicketsales();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), Double.valueOf(sp.getPalvellutAsiakkaat())));


                break;
            case 3:

                sp = sim.getEntrance();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getPalvelunKeskiaika()));

                sp = sim.getMetro();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getPalvelunKeskiaika()));

                sp = sim.getTicketcheck();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getPalvelunKeskiaika()));

                sp = sim.getTicketsales();
                series.getData().add(new XYChart.Data<>(sp.getPalvelupiste().name(), sp.getPalvelunKeskiaika()));

                break;
        }

        i.getbarChart().getData().add(series);

        //asetetaan oikeaan palkkiin simuloinnin tiedot

        i.setInfo(sim);

    }

    @Override
    public void initchart(graphviewcontroller i) {
        SimulaattoriDAO sdao = new SimulaattoriDAO();

        List<Simulaattori> simlist = sdao.listaaSimulaattorit();

        if (simlist == null || simlist.size() == 0) {
            Alert a = new Alert(Alert.AlertType.NONE);
            a.setAlertType(Alert.AlertType.ERROR);
            a.setContentText("Sql tietokannassa ei simulaation tuloksia! >:D");
            a.show();
            return;

        }
        i.getListView().getItems().clear();
        for (Simulaattori sim : simlist) {

            i.getListView().getItems().add("Simulaatio " + sim.getId());

        }

        if (i.getListView().getSelectionModel().getSelectedIndex() < 0){
            asetachart(i, 1);
        }


    }

    /**
     * Asettaa Taulukko ikkunassa sijaitsevan listviewein
     * @param i referenssi graphviewcontrolleriin
     */
    @Override
    public void dChart(graphviewcontroller i){
        ISimulaattoriDAO sdao = new SimulaattoriDAO();
        int id = i.getListView().getSelectionModel().getSelectedIndex();

        if (id < 0){
            return;
        }

        String ids = (String) i.getListView().getItems().get(i.getListView().getSelectionModel().getSelectedIndex());
        try {
            id = Integer.parseInt(ids.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        sdao.poistaSimulaattori(id);

        initchart(i);
    }
}
