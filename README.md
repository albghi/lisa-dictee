# Lisa Dictée

Dictée vocale par IA pour Android, par AG Ultra Magnus. Tu parles, Lisa écrit, proprement, là où se trouve ton curseur.

- **Clavier Lisa** : un gros micro, puis ✗ pour annuler ou ✓ pour écrire.
- **Bulle Lisa** (service d'accessibilité) : apparaît quand on touche une zone de texte, avec n'importe quel clavier.
- **Mode Lisa** : « Lisa, tâche, Tano, appeler le comptable » crée la tâche dans Lisa Operator.

La voix part vers la fonction `dictee` (transcription ElevenLabs, nettoyage par Claude). Aucune clé n'est dans ce code : elle se colle une fois dans l'application.

Chaque envoi sur `main` fabrique l'APK (GitHub Actions) et le publie dans les Releases.
