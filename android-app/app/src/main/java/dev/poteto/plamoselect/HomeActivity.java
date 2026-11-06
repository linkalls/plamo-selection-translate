package dev.poteto.plamoselect;

/**
 * A full-screen translation home, distinct from the small context-menu popup.
 * Both entry points use the same translation engine, language handling, and
 * view components so their behavior stays consistent.
 */
public final class HomeActivity extends TranslateActivity {
    @Override
    protected boolean isHomeScreen() {
        return true;
    }
}
