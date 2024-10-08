/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com)
 * SPDX-License-Identifier: Apache-2.0
 */
package com.vegardit.copycat.util;

import java.awt.AWTError;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.TrayIcon.MessageType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.eclipse.jdt.annotation.Nullable;

import io.github.autocomplete1.PowerShell;
import io.github.autocomplete1.PowerShellNotAvailableException;
import net.sf.jstuff.core.SystemUtils;
import net.sf.jstuff.core.logging.Logger;

/**
 * @author Sebastian Thomschke, Vegard IT GmbH
 */
public final class DesktopNotifications {

   private static final Logger LOG = Logger.create();

   private static final @Nullable TrayIcon TRAY_ICON;
   private static final boolean IS_POWERSHELL_AVAILABLE;
   private static final @Nullable Path APP_ICON;

   static {
      if (isSupported()) {
         final var trayIcon = TRAY_ICON = new TrayIcon(Toolkit.getDefaultToolkit().createImage(DesktopNotifications.class.getResource(
            "/copycat16x16.png")));
         trayIcon.setImageAutoSize(true);
         final var popup = new PopupMenu();
         popup.add("Copycat");
         popup.addSeparator();
         final var exitItem = new MenuItem("Exit (CTRL+C)");
         popup.add(exitItem);
         exitItem.addActionListener(e -> sun.misc.Signal.raise(new sun.misc.Signal("INT")));
         trayIcon.setPopupMenu(popup);
         new Thread(() -> {
            try {
               SystemTray.getSystemTray().add(TRAY_ICON);
            } catch (final Exception ex) {
               LOG.warn(ex);
            }
         }).start();
      } else {
         TRAY_ICON = null;
      }

      if (SystemUtils.IS_OS_WINDOWS) {
         boolean isPSAvailable = false;
         Path appIcon = null;
         try (PowerShell powerShell = PowerShell.openSession()) {
            isPSAvailable = true;
            try (var is = DesktopNotifications.class.getResourceAsStream("/copycat16x16.png")) {
               appIcon = Files.createTempFile("copycat", "appicon");
               Files.copy(is, appIcon, StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (final IOException | PowerShellNotAvailableException ex) {
            // ignore
         }
         IS_POWERSHELL_AVAILABLE = isPSAvailable;
         APP_ICON = appIcon;
      } else {
         IS_POWERSHELL_AVAILABLE = false;
         APP_ICON = null;
      }
   }

   public static boolean isSupported() {
      try {
         return SystemTray.isSupported();
      } catch (final UnsatisfiedLinkError | AWTError ex) {
         // https://github.com/oracle/graal/issues/2842
         LOG.warn(ex);
         return false;
      }
   }

   public static void setTrayIconToolTip(final String message) {
      if (TRAY_ICON != null) {
         TRAY_ICON.setToolTip(message);
      }
   }

   public static synchronized void showSticky(final MessageType level, final String title, final String message) {
      if (IS_POWERSHELL_AVAILABLE) {
         try (PowerShell powerShell = PowerShell.openSession()) {
            powerShell.executeScript(new BufferedReader(new StringReader("" //
                  + "Add-Type -AssemblyName System.Windows.Forms;" //
                  + "Add-Type -AssemblyName System.Drawing;" //
                  + "$msg=New-Object System.Windows.Forms.NotifyIcon;" //
                  + (APP_ICON == null //
                        ? "$msg.Icon=[System.Drawing.SystemIcons]::Application;" //  https://docs.microsoft.com/en-us/dotnet/api/system.drawing.systemicons
                        : "$appIcon=[System.Drawing.Image]::FromFile('" + APP_ICON + "');" //
                              + "$msg.Icon=[System.Drawing.Icon]::FromHandle($appIcon.GetHicon());" //
                  ) //
                  + "$msg.BalloonTipTitle='[copycat] " + title.replace("'", "\\'") + "';" //
                  + "$msg.BalloonTipText='" + message.replace("'", "\\'") + "';" //
                  + "$msg.BalloonTipIcon='" + level + "';" // https://docs.microsoft.com/en-us/dotnet/api/system.windows.forms.tooltipicon
                  + "$msg.Visible=$True;" //
                  + "$msg.ShowBalloonTip(5000);" //
            )));
            return;
         } catch (final RuntimeException ex) {
            LOG.warn(ex);
         }
      }

      showTransient(level, title, message);
   }

   public static synchronized void showTransient(final MessageType level, final String title, final String message) {
      if (TRAY_ICON != null) {
         TRAY_ICON.displayMessage("[copycat] " + title, message, level);
      }
   }

   private DesktopNotifications() {
   }
}
