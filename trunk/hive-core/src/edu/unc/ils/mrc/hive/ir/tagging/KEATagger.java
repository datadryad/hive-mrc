package edu.unc.ils.mrc.hive.ir.tagging;

import java.util.List;

import edu.unc.ils.mrc.hive.api.SKOSScheme;
import kea.main.KEAKeyphraseExtractor;
import kea.stemmers.PorterStemmer;
import kea.stopwords.StopwordsEnglish;

public class KEATagger implements Tagger{

	private KEAKeyphraseExtractor ke;
	private String vocabulary;

	public KEATagger(String dirName, String modelName, String stopwordsPath,
			SKOSScheme schema) {
		
		this.vocabulary = schema.getLongName();
		this.ke = new KEAKeyphraseExtractor(schema);

		// A. required arguments (no defaults):

		// 1. Name of the directory -- give the path to your directory with
		// documents
		// documents should be in txt format with an extention "txt".
		// Note: keyphrases with the same name as documents, but extension "key"
		// one keyphrase per line!

		this.ke.setDirName(dirName);

		// 2. Name of the model -- give the path to the model
		this.ke.setModelName(modelName);

		// 3. Name of the vocabulary -- name of the file (without extension)
		// that is stored in VOCABULARIES
		// or "none" if no Vocabulary is used (free keyphrase extraction).
		this.ke.setVocabulary(schema.getName().toLowerCase());

		// 4. Format of the vocabulary in 3. Leave empty if vocabulary = "none",
		// use "skos" or "txt" otherwise.
		this.ke.setVocabularyFormat("skos");

		// B. optional arguments if you want to change the defaults
		// 5. Encoding of the document
		this.ke.setEncoding("UTF-8");

		// 6. Language of the document -- use "es" for Spanish, "fr" for French
		// or other languages as specified in your "skos" vocabulary
		this.ke.setDocumentLanguage("en"); // es for Spanish, fr for French

		// 7. Stemmer -- adjust if you use a different language than English or
		// want to alterate results
		// (We have obtained better results for Spanish and French with
		// NoStemmer)
		this.ke.setStemmer(new PorterStemmer());

		// 8. Stopwords
		this.ke.setStopwords(new StopwordsEnglish(stopwordsPath));
		
		// 9. Number of Keyphrases to extract
		this.ke.setNumPhrases(10);

		// 10. Set to true, if you want to compute global dictionaries from the
		// test collection
		this.ke.setBuildGlobal(false);
		this.ke.setAdditionalInfo(true);

		try {
			this.ke.loadModel();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		this.ke.loadThesaurus();
	}

	public void extractKeyphrases() {
		try {
			this.ke.extractKeyphrases(ke.collectStems());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public String getVocabulary() {
		return this.vocabulary;
	}

	@Override
	public List<String> extractKeyphrases(String text) {
		return null;
		
	}

}