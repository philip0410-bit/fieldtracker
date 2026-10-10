# FieldTracker

Suivi de déplacements sur un terrain de sport balisé au GPS (téléphone Android + montre Wear OS).

## Installer
1. Onglet **Releases** (ou **Actions** → dernier build) : télécharger `FieldTracker-telephone.apk` et `FieldTracker-montre.apk`.
2. Téléphone : ouvrir l'APK et autoriser l'installation.
3. Montre (Galaxy Watch) : activer le débogage ADB Wi-Fi sur la montre puis `adb install FieldTracker-montre.apk`.

## Utiliser
Sports → choisir le sport → Balisage (4 coins, bouton OK sur le téléphone ou la montre) → Start → Terminer → Analyse.

Traqueurs pro (STATSports APEX, MyCoach…) : importer leur export CSV / GPX / TCX depuis l'onglet Sports.
Samsung Health : via Health Connect (FC lue et séances envoyées).
