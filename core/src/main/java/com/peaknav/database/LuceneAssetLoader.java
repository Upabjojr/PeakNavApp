package com.peaknav.database;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.IndexSearcher;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;

public class LuceneAssetLoader {
    private final String assetFolderName = "geonames_index.362";
    private final File localDir = new File(Gdx.files.external("").file(), assetFolderName);

    private void copyAssetsToInternalStorage() throws IOException {

        if (!localDir.exists()) {
            localDir.mkdirs();
        }

        FileHandle index = Gdx.files.internal(assetFolderName + "/filelist.txt");
        for (String name : index.readString().split("\\r?\\n")) {
            FileHandle assetFile = Gdx.files.internal(assetFolderName + "/" + name);
            File dest = new File(localDir, assetFile.name());
            if (!dest.exists()) {
                try (InputStream is = assetFile.read();
                     FileOutputStream os = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        os.write(buffer, 0, len);
                    }
                }
            }
        }
    }

    private IndexSearcher getIndexSearcherNoCopy() throws IOException {
        // 3. Open Lucene FSDirectory
        // FSDirectory dir = FSDirectory.open(Paths.get(localDir.getAbsolutePath()));
        FSDirectory dir = FSDirectory.open(localDir.getAbsoluteFile());
        IndexReader reader = IndexReader.open(dir);
        return new IndexSearcher(reader);
    }

    public IndexSearcher getIndexSearcher() {
        try {
            copyAssetsToInternalStorage();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            return getIndexSearcherNoCopy();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
