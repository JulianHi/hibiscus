/**********************************************************************
 * $Source: /cvsroot/hibiscus/hibiscus/src/de/willuhn/jameica/hbci/passports/pintan/server/PassportHandleImpl.java,v $
 * $Revision: 1.14 $
 * $Date: 2011/05/27 12:39:59 $
 * $Author: willuhn $
 * $Locker:  $
 * $State: Exp $
 *
 * Copyright (c) by willuhn.webdesign
 * All rights reserved
 *
 **********************************************************************/
package de.willuhn.jameica.hbci.passports.pintan.server;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;

import org.kapott.hbci.callback.HBCICallback;
import org.kapott.hbci.manager.HBCIHandler;
import org.kapott.hbci.passport.AbstractHBCIPassport;
import org.kapott.hbci.passport.AbstractPinTanPassport;
import org.kapott.hbci.passport.HBCIPassport;

import de.willuhn.datasource.GenericIterator;
import de.willuhn.jameica.hbci.HBCI;
import de.willuhn.jameica.hbci.HBCICallbackSWT;
import de.willuhn.jameica.hbci.gui.DialogFactory;
import de.willuhn.jameica.hbci.passport.PassportHandle;
import de.willuhn.jameica.hbci.passports.pintan.ChipTANDialog;
import de.willuhn.jameica.hbci.passports.pintan.PinTanConfigFactory;
import de.willuhn.jameica.hbci.passports.pintan.PtSecMech;
import de.willuhn.jameica.hbci.passports.pintan.PtSecMechDialog;
import de.willuhn.jameica.hbci.passports.pintan.SelectConfigDialog;
import de.willuhn.jameica.hbci.passports.pintan.TANDialog;
import de.willuhn.jameica.hbci.passports.pintan.TanMediaDialog;
import de.willuhn.jameica.hbci.passports.pintan.rmi.PinTanConfig;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Protokoll;
import de.willuhn.jameica.hbci.server.Converter;
import de.willuhn.jameica.messaging.StatusBarMessage;
import de.willuhn.jameica.plugin.AbstractPlugin;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.OperationCanceledException;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.I18N;

/**
 * Implementierung des Passports vom Typ "Chipkarte" (DDV).
 */
public class PassportHandleImpl extends UnicastRemoteObject implements PassportHandle
{
  private final static I18N i18n = Application.getPluginLoader().getPlugin(HBCI.class).getResources().getI18N();

	private HBCIPassport hbciPassport = null;
	private HBCIHandler handler = null;

	private PassportImpl passport = null;
  private PinTanConfig config   = null;

  /**
   * ct.
   * @param passport
   * @throws RemoteException
   */
  public PassportHandleImpl(PassportImpl passport) throws RemoteException {
    super();
		this.passport = passport;
  }

  /**
   * @param config
   * @throws RemoteException
   */
  public PassportHandleImpl(PinTanConfig config) throws RemoteException {
    super();
    this.config = config;
  }

  /**
   * @see de.willuhn.jameica.hbci.passport.PassportHandle#open()
   */
  public HBCIHandler open() throws RemoteException, ApplicationException
  {

		if (isOpen())
			return handler;

		Logger.info("open pin/tan passport");
		try {
	
      if (config == null && this.passport == null)
        throw new ApplicationException(i18n.tr("Keine Konfiguration oder Konto ausgew�hlt"));

      if (config == null && this.passport != null && this.passport.getKonto() != null)
        config = PinTanConfigFactory.findByKonto(this.passport.getKonto());


      // Mh, nichts da zum Laden, dann fragen wir mal den User
      if (config == null)
      {
        GenericIterator list = PinTanConfigFactory.getConfigs();

        if (list == null || list.size() == 0)
          throw new ApplicationException(i18n.tr("Bitte legen Sie zuerst eine PIN/TAN-Konfiguration an"));
        
        // Wir haben nur eine Config, dann brauchen wir den User nicht fragen
        if (list.size() == 1)
        {
          config = (PinTanConfig) list.next();
        }
        else
        {
          SelectConfigDialog d = new SelectConfigDialog(SelectConfigDialog.POSITION_CENTER);
          try
          {
            config = (PinTanConfig) d.open();
          }
          catch (OperationCanceledException oce)
          {
            throw oce;
          }
          catch (Exception e)
          {
            Logger.error("error while choosing config",e);
            throw new ApplicationException(i18n.tr("Fehler bei der Auswahl der PIN/TAN-Konfiguration"));
          }
        }
      }

      if (config == null)
        throw new ApplicationException(i18n.tr("Keine PIN/TAN-Konfiguration f�r dieses Konto definiert"));
      
			Logger.debug("using passport file " + config.getFilename());

      AbstractPlugin plugin = Application.getPluginLoader().getPlugin(HBCI.class);
      HBCICallback callback = ((HBCI)plugin).getHBCICallback();
      if (callback != null && (callback instanceof HBCICallbackSWT))
        ((HBCICallbackSWT)callback).setCurrentHandle(this);

      hbciPassport = config.getPassport();

      {
        AbstractHBCIPassport ap = (AbstractHBCIPassport) hbciPassport;
        
        // Wir speichern die verwendete PIN/TAN-Config im Passport. Dann wissen wir
        // spaeter in den HBCI-Callbacks noch, aus welcher Config der Passport
        // erstellt wurde. Wird z.Bsp. vom Payment-Server benoetigt.
        ap.setPersistentData(CONTEXT_CONFIG,config);
        
        String cannationalacc = config.getCustomProperty("cannationalacc");
        if (cannationalacc != null)
          ap.setPersistentData("cannationalacc",cannationalacc);
      }

			String hbciVersion = config.getHBCIVersion();
			if (hbciVersion == null || hbciVersion.length() == 0)
				hbciVersion = "300";

      Logger.info("[PIN/TAN] url         : " + config.getURL());
      Logger.info("[PIN/TAN] blz         : " + config.getBLZ());
      Logger.info("[PIN/TAN] filter      : " + config.getFilterType());
      Logger.info("[PIN/TAN] HBCI version: " + hbciVersion);

      //////////////////////
      // BUGZILLA 831
      // Siehe auch Stefans Mail vom 10.03.2010 - Betreff "Re: [hbci4java] Speicherung des TAN-Verfahrens im PIN/TAN-Passport-File?"
      String secmech = config.getSecMech();
      if (secmech != null && secmech.trim().length() == 0)
        secmech = null; // nur um sicherzustellen, dass kein Leerstring drinsteht

      Logger.info("[PIN/TAN] tan sec mech: " + secmech);
      ((AbstractPinTanPassport)hbciPassport).setCurrentTANMethod(secmech);
      //////////////////////


			handler=new HBCIHandler(hbciVersion,hbciPassport);
			return handler;
		}
    catch (RemoteException re)
    {
      close();
      throw re;
    }
    catch (ApplicationException ae)
    {
      close();
      throw ae;
    }
		catch (Exception e)
		{
			close();
			Logger.error("error while opening pin/tan passport",e);
			throw new RemoteException("error while opening pin/tan passport",e);
		}
  }

  /**
   * @see de.willuhn.jameica.hbci.passport.PassportHandle#isOpen()
   */
  public boolean isOpen() throws RemoteException {
		return handler != null && hbciPassport != null;
	}

  /**
   * @see de.willuhn.jameica.hbci.passport.PassportHandle#close()
   */
  public void close() throws RemoteException {
		if (hbciPassport == null && handler == null)
			return;

		this.handleChangedCustomerData();
		
		try {
			Logger.info("closing pin/tan passport");
			handler.close();
		}
		catch (Exception e) {/*useless*/}
		hbciPassport = null;
		handler = null;

    AbstractPlugin plugin = Application.getPluginLoader().getPlugin(HBCI.class);
    HBCICallback callback = ((HBCI)plugin).getHBCICallback();
    if (callback != null && (callback instanceof HBCICallbackSWT))
      ((HBCICallbackSWT)callback).setCurrentHandle(null);
    
    Logger.info("pin/tan passport closed");
  }
  
  /**
   * Behandelt die GAD-spezifische Rueckmeldung zur Aenderung der Kundenkennung
   */
  private void handleChangedCustomerData()
  {
    if (hbciPassport == null && handler == null)
      return;

    Object o = ((AbstractHBCIPassport)hbciPassport).getPersistentData(CONTEXT_USERID_CHANGED);
    if (o == null)
      return;

    try
    {
      String changes = o.toString();
      int pos = changes.indexOf("|");
      if (pos == -1)
        return;
      
      String userId = changes.substring(0,pos);
      String custId = changes.substring(pos+1);
      if (userId.length() == 0 || custId.length() == 0)
        return;
      
      String custOld = hbciPassport.getCustomerId();
      String userOld = hbciPassport.getUserId();

      String text = i18n.tr("Die Bank hat mitgeteilt, dass sich die Benutzer- und Kundenkennung Ihres\n" +
      		                  "Bank-Zugangs ge�ndert hat. Die neuen Zugangsdaten lauten:\n\n" +
                            "  Alte Kundenkennung: {0}\n" +
                            "  Neue Kundenkennung: {1}\n\n" +
      		                  "  Alte Benutzerkennung: {2}\n" +
                            "  Neue Benutzerkennung: {3}\n\n" +
      		                  "M�chten Sie die ge�nderten Zugangsdaten jetzt �bernehmen?");
      
      boolean b = Application.getCallback().askUser(text,new String[]{custOld,custId,userOld,userId});
      if (!b)
        return;
      
      // 1) Aenderung im Passport selbst
      Logger.info("applying new customer data to passport");
      hbciPassport.setCustomerId(custId);
      hbciPassport.setUserId(userId);
      hbciPassport.saveChanges();
      
      // 2) UPD nach Zugangsdaten durchsuchen
      Properties upd = hbciPassport.getUPD();
      Enumeration e = upd.keys();
      int count = 0;
      while (e.hasMoreElements())
      {
        String key = (String) e.nextElement();
        String value = upd.getProperty(key);
        if (value == null || value.length() == 0)
          continue;
        
        if (value.equals(custOld))
        {
          Logger.info("updating UPD entry " + key + " with new customer/user id");
          upd.setProperty(key,custId);
          count++;
        }
        else if (value.equals(userOld))
        {
          Logger.info("updating UPD entry " + key + " with new customer/user id");
          upd.setProperty(key,userId);
          count++;
        }
      }
      Logger.info("updated " + count + " entries in UPD");
      
      // 3) Kundenkennung in zugeordneten Konten aktualisieren
      count = 0;
      org.kapott.hbci.structures.Konto[] konten = hbciPassport.getAccounts();
      if (konten != null && konten.length > 0)
      {
        for (org.kapott.hbci.structures.Konto konto:konten)
        {
          Konto k = Converter.HBCIKonto2HibiscusKonto(konto, PassportImpl.class);
          if (!k.isNewObject())
          {
            k.setKundennummer(custId);
            k.store();
            Logger.info("updating customerid in account ID " + k.getID());
            k.addToProtokoll(i18n.tr("Ge�nderte Kundenkennung im Konto - neu: {0}, alt: {1}",custId,custOld),Protokoll.TYP_SUCCESS);
            count++;
          }
        }
      }
      Logger.info("updated customerid in " + count + " accounts");

      // 4) Aenderung im Konto protokollieren
      Konto konto = this.passport != null ? this.passport.getKonto() : null;
      if (konto != null)
      {
        konto.addToProtokoll(i18n.tr("Ge�nderte Kundenkennung im Bankzugang - neu: {0}, alt: {1}",custId,custOld),Protokoll.TYP_SUCCESS);
        konto.addToProtokoll(i18n.tr("Ge�nderte Benutzerkennung im Bankzugang - neu: {0}, alt: {1}",userId,userOld),Protokoll.TYP_SUCCESS);
        Logger.info("logged changes in account");
      }
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(i18n.tr("Ge�nderte Zugangsdaten erfolgreich �bernommen"),StatusBarMessage.TYPE_SUCCESS));
    }
    catch (Exception e)
    {
      Logger.error("error while applying new user-/customer data",e);
      Application.getMessagingFactory().sendMessage(new StatusBarMessage(i18n.tr("Fehler beim �bernehmen der ge�nderten Zugangsdaten: {0}",e.getMessage()),StatusBarMessage.TYPE_ERROR));
    }
    finally
    {
      // aus den Context-Daten entfernen, wenn wir es behandelt haben
      ((AbstractHBCIPassport)hbciPassport).setPersistentData(CONTEXT_USERID_CHANGED,null);
    }
  }

  /**
   * @see de.willuhn.jameica.hbci.passport.PassportHandle#getKonten()
   */
  public Konto[] getKonten() throws RemoteException, ApplicationException
  {
  	Logger.info("reading accounts from pin/tan passport");
		try {
			open();
			org.kapott.hbci.structures.Konto[] konten = hbciPassport.getAccounts();
			if (konten == null || konten.length == 0)
			{
				Logger.info("no accounts found");
				return new Konto[]{};
			}

			ArrayList result = new ArrayList();
			Konto k = null;
			for (int i=0;i<konten.length;++i)
			{
				k = Converter.HBCIKonto2HibiscusKonto(konten[i], PassportImpl.class);
				Logger.debug("found account " + k.getKontonummer());
				result.add(k);
			}
			return (Konto[]) result.toArray(new Konto[result.size()]);
		}
		finally
		{
			try {
				close();
			}
			catch (RemoteException e2) {/*useless*/}
		}
  }

  /**
   * @see de.willuhn.jameica.hbci.passport.PassportHandle#callback(org.kapott.hbci.passport.HBCIPassport, int, java.lang.String, int, java.lang.StringBuffer)
   */
  public boolean callback(HBCIPassport passport, int reason, String msg, int datatype, StringBuffer retData) throws Exception
  {
    switch (reason)
    {
      case HBCICallback.NEED_PT_PIN:
      {
        retData.replace(0,retData.length(),DialogFactory.getPIN(passport));
        return true;
      }

      // BUGZILLA 62
      case HBCICallback.NEED_PT_TAN:
      {
        TANDialog dialog = null;
        
        String flicker = retData.toString();
        if (flicker != null && flicker.length() > 0)
        {
          // Wir haben einen Flicker-Code. Also zeigen wir den Flicker-Dialog statt
          // dem normalen TAN-Dialog an
          Logger.debug("got flicker code " + flicker + ", using optical chiptan dialog");
          dialog = new ChipTANDialog(config,flicker);
        }
        
        // regulaerer TAN-Dialog
        if (dialog == null)
        {
          Logger.debug("using regular tan dialog");
          dialog = new TANDialog(config);
        }
        
        dialog.setText(msg);
        retData.replace(0,retData.length(),(String)dialog.open());
        return true;
      }

      // BUGZILLA 200
      case HBCICallback.NEED_PT_SECMECH:
      {
        if (config != null)
        {
          String type = config.getSecMech();
          if (type != null && type.length() > 0)
          {
            // Wir checken vorher noch, ob es das TAN-Verfahren ueberhaupt noch gibt
            PtSecMech mech = PtSecMech.contains(retData.toString(),type);
            if (mech != null)
            {
              // Jepp, gibts noch
              retData.replace(0,retData.length(),type);
              return true;
            }
          }
        }
        
        PtSecMechDialog ptd = new PtSecMechDialog(config,retData.toString());
        retData.replace(0,retData.length(),(String) ptd.open());
        return true;
      }
        
      // BUGZILLA 827
      case HBCICallback.NEED_PT_TANMEDIA:
      {
        if (config != null)
        {
          String media =  config.getTanMedia();
          if (media != null && media.length() > 0)
          {
            // OK, die nehmen wir
            retData.replace(0,retData.length(),media);
            return true;
          }
        }

        TanMediaDialog tmd = new TanMediaDialog(config);
        retData.replace(0,retData.length(),(String) tmd.open());
        return true;
      }
    }
    
    return false;
  }

}


/**********************************************************************
 * $Log: PassportHandleImpl.java,v $
 * Revision 1.14  2011/05/27 12:39:59  willuhn
 * *** empty log message ***
 *
 * Revision 1.13  2011-05-27 10:51:02  willuhn
 * @N Erster Support fuer optisches chipTAN
 *
 * Revision 1.12  2011-05-26 08:52:26  willuhn
 * @N Challenge HHDuc fuer Diagnose-Zwecke loggen
 *
 * Revision 1.11  2011-05-24 09:06:11  willuhn
 * @C Refactoring und Vereinfachung von HBCI-Callbacks
 *
 * Revision 1.10  2011-05-19 07:59:53  willuhn
 * @C optisches chipTAN voruebergehend deaktiviert, damit ich in Ruhe in hbci4Java an der Unterstuetzung weiterarbeiten kann
 *
 * Revision 1.9  2011-05-10 11:16:55  willuhn
 * @C Fallback auf normalen TAN-Dialog, wenn der Flicker-Code nicht lesbar ist
 *
 * Revision 1.8  2011-05-09 17:27:39  willuhn
 * @N Erste Vorbereitungen fuer optisches chipTAN
 *
 * Revision 1.7  2011-05-09 09:35:15  willuhn
 * @N BUGZILLA 827
 *
 * Revision 1.6  2010-12-15 13:17:25  willuhn
 * @N Code zum Parsen der TAN-Verfahren in PtSecMech ausgelagert. Wenn ein TAN-Verfahren aus Vorauswahl abgespeichert wurde, wird es nun nur noch dann automatisch verwendet, wenn es in der aktuellen Liste der TAN-Verfahren noch enthalten ist. Siehe http://www.onlinebanking-forum.de/phpBB2/viewtopic.php?t=12545
 *
 * Revision 1.5  2010-10-27 10:25:10  willuhn
 * @C Unnoetiges Fangen und Weiterwerfen von Exceptions
 *
 * Revision 1.4  2010-09-29 23:43:34  willuhn
 * @N Automatisches Abgleichen und Anlegen von Konten aus KontoFetchFromPassport in KontoMerge verschoben
 * @N Konten automatisch (mit Rueckfrage) anlegen, wenn das Testen der HBCI-Konfiguration erfolgreich war
 * @N Config-Test jetzt auch bei Schluesseldatei
 * @B in PassportHandleImpl#getKonten() wurder der Converter-Funktion seit jeher die falsche Passport-Klasse uebergeben. Da gehoerte nicht das Interface hin sondern die Impl
 *
 * Revision 1.3  2010-09-08 15:04:52  willuhn
 * @N Config des Sicherheitsmediums als Context in Passport speichern
 *
 * Revision 1.2  2010-09-07 15:17:08  willuhn
 * @N GUI-Cleanup
 *
 * Revision 1.1  2010/06/17 11:38:16  willuhn
 * @C kompletten Code aus "hbci_passport_pintan" in Hibiscus verschoben - es macht eigentlich keinen Sinn mehr, das in separaten Projekten zu fuehren
 *
 * Revision 1.15  2010/03/10 15:42:14  willuhn
 * @N BUGZILLA 831
 **********************************************************************/