package it.trsoftware.garanzie;

/** Destinazione: cartella condivisa del NAS (SMB). */
final class NasStore implements Store {

    private final Nas.Cfg cfg;

    NasStore(Nas.Cfg cfg) {
        this.cfg = cfg;
    }

    @Override public String test() throws Exception { return Nas.test(cfg); }

    @Override public String ping() throws Exception { return Nas.ping(cfg); }

    @Override public String list() throws Exception { return Nas.list(cfg); }

    @Override public byte[] read(String relPath) throws Exception { return Nas.read(cfg, relPath); }

    @Override public String writeFile(String relDir, String name, byte[] data) throws Exception {
        return Nas.writeFile(cfg, relDir, name, data);
    }

    @Override public void writeText(String relDir, String name, String text) throws Exception {
        Nas.writeText(cfg, relDir, name, text);
    }

    @Override public String deleteFolder(String relDir) throws Exception { return Nas.deleteFolder(cfg, relDir); }
}
