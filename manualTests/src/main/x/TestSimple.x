module TestSimple
    {
    @Inject Console console;

    package crypto import crypto.xtclang.org;

    import crypto.*;
    import crypto.KeyStore.Info;

    void run(String[] args = ["password"])
        {
        @Inject Directory curDir;
        File storeHello = curDir.fileFor("data/hello/https.p12");
        reportKeyStore(storeHello, args[0]);

        @Inject Directory homeDir;
        File storePlatform = homeDir.fileFor("xqiz.it/platform/certs.p12");
        reportKeyStore(storePlatform, args[0]);
        }

    void reportKeyStore(File store, String password)
        {
        @Inject(opts=new Info(store.contents, password)) KeyStore keystore;

        console.print("**** Certificates ****");
        for (Certificate cert : keystore.certificates)
            {
            console.print($"certificate={cert}");
            if (CryptoKey key := cert.containsKey())
                {
                console.print($"key={key}\n");
                }
            }

        console.print("**** Keys ****");
        for (String name : keystore.keyNames)
            {
            assert CryptoKey key := keystore.getKey(name);
            console.print($"key={key} {&key.actualClass}\n");
            }
        }
    }