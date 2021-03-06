package kea.vocab;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.dbcp.ConnectionFactory;
import org.apache.commons.dbcp.DriverManagerConnectionFactory;
import org.apache.commons.dbcp.PoolableConnectionFactory;
import org.apache.commons.dbcp.PoolingDriver;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.pool.ObjectPool;
import org.apache.commons.pool.impl.GenericObjectPool;
import org.openrdf.concepts.skos.core.Concept;
import org.openrdf.elmo.ElmoModule;
import org.openrdf.elmo.sesame.SesameManager;
import org.openrdf.elmo.sesame.SesameManagerFactory;
import org.openrdf.repository.Repository;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.sail.nativerdf.NativeStore;
import org.perf4j.StopWatch;
import org.perf4j.log4j.Log4JStopWatch;

import edu.unc.ils.mrc.hive.api.SKOSScheme;
import edu.unc.ils.mrc.hive2.api.HiveVocabulary;
import edu.unc.ils.mrc.hive2.api.impl.HiveH2IndexImpl;
import edu.unc.ils.mrc.hive2.api.impl.HiveVocabularyImpl;

import kea.stemmers.PorterStemmer;
import kea.stemmers.Stemmer;
import kea.stopwords.Stopwords;
import kea.stopwords.StopwordsEnglish;

/**
 * Vocabulary implementation backed by an embedded H2 database
 *     http://www.h2database.com/
 * 
 * The database table structure is identical to the default Jena-based
 * Vocabulary implementation supplied with the KEA++ distribution.
 * 
 * Like the Sesame-based Vocabulary implementation developed for HIVE,
 * the H2 implementation reads concepts from a Sesame database, loaded
 * from original SKOS RDF/XML. This is actually likely unnecessary,
 * but in place today to replicate existing behavior as closely as possible.
 * 
 * Insert directly into H2 for large vocabularies is slow. Instead,
 * temporary delimited files are created and bulk-loaded using CSVREAD.
 *
 */
public class VocabularyH2 extends Vocabulary 
{
	private static final Log logger = LogFactory.getLog(VocabularyH2.class);
			
	private static final long serialVersionUID = 7089304477568443576L;

	private SesameManager manager;
	
	String name;
	/* OutputStreamWriter used during H2 database initialization */
	OutputStreamWriter vocabularyEN;
	OutputStreamWriter vocabularyENrev;
	OutputStreamWriter vocabularyUSE;
	OutputStreamWriter vocabularyREL;
	
	File h2;
	
	HiveH2IndexImpl h2Index = null;

	/**
	 * Constructs a VocabularyH2 instance 
	 * @param h2path Path to the H2 database for the current vocabulary
	 * @param documentLanguage Language (not currently used)
	 * @param manager SesameManager for the current vocabulary
	 * @throws ClassNotFoundException
	 * @throws SQLException
	 */
	public VocabularyH2(String name, String basePath, String documentLanguage, SesameManager manager) 
		throws ClassNotFoundException, SQLException 
	{
		super(documentLanguage);
		this.manager = manager;
		this.name = name;
		HiveVocabularyImpl hv = HiveVocabularyImpl.getInstance(basePath, name);
		h2Index = (HiveH2IndexImpl)hv.getH2Index();
	}
	
	public VocabularyH2(SKOSScheme scheme, String documentLanguage) 
		throws ClassNotFoundException, SQLException 
	{
		super(documentLanguage);
		this.manager = scheme.getManager();
		this.name = scheme.getName();
		HiveVocabularyImpl hv = (HiveVocabularyImpl)scheme.getHiveVocabulary();
		if (hv != null)
			h2Index = (HiveH2IndexImpl)hv.getH2Index();
		
		setStopwords(new StopwordsEnglish(scheme.getStopwordsPath()));
		
		try
		{
			Class cls = Class.forName(scheme.getKeaStemmerClass());
			Stemmer stemmer = (Stemmer)cls.newInstance();
			setStemmer(stemmer);
		} catch (Exception e) {
			logger.error("Error instantiating stemmer: " + e);
			setStemmer(new PorterStemmer());
		}
	}	
	
	/**
	 * Returns a connection from the pool
	 * @return
	 * @throws Exception
	 */
	protected Connection getConnection() throws SQLException {
		
		logger.trace("getConnection()");
		return h2Index.getConnection();
	}
		
	
	@Override
	public void initialize() 
	{
		try
		{
			if (!exists()) {
				buildSKOS();
			} else {
				logger.info("H2 database exists, skipping H2 initialization");
			}
			
		} catch (Exception e) {
			logger.error(e);
		}
	}
	
	protected boolean exists() {

		if (!h2Index.exists())
			return false;
		else
		{
			Connection con = null;
			try {
				con = getConnection();
				DatabaseMetaData dm = con.getMetaData();
				ResultSet rs = dm.getTables(null, null, "VOCABULARY_EN", null);
				if (rs.next())
					return true;
				else
					return false;
			} catch (SQLException e) {
			} finally {
				if (con != null) {
					try {
						con.close();
					} catch (Exception e) { }
				}
			}
		}
		return false;
			
	}

	/**
	 * Initializes the H2 databases for the current vocabulary from an 
	 * existing Sesame store.
	 */
	@Override
	public void buildSKOS() throws Exception {
		StopWatch stopwatch = new Log4JStopWatch();
		
		logger.info("buildSKOS");
		
		// Temporary files used to store KEA++ maps prior to import into H2
		File fileEN = File.createTempFile("vocabularyEN", null);
		File fileENrev = File.createTempFile("vocabularyENrev", null);
		File fileUSE = File.createTempFile("vocabularyUSE", null);
		File fileREL = File.createTempFile("vocabularyREL", null);
		boolean hasRelated = false;
		boolean hasAltLabels = false;
		
		try {
			
			logger.info("Creating temp file " + fileEN.getAbsolutePath());
			logger.info("Creating temp file " + fileENrev.getAbsolutePath());
			logger.info("Creating temp file " + fileUSE.getAbsolutePath());
			logger.info("Creating temp file " + fileREL.getAbsolutePath());
			
			vocabularyEN = new OutputStreamWriter(new FileOutputStream(fileEN), "UTF-8");
			vocabularyENrev = new OutputStreamWriter(new FileOutputStream(fileENrev), "UTF-8");
			vocabularyUSE = new OutputStreamWriter(new FileOutputStream(fileUSE), "UTF-8");
			vocabularyREL = new OutputStreamWriter(new FileOutputStream(fileREL), "UTF-8");
		
			int count = 1;

			// Iterate through all concepts in the vocabulary and write
			// to temporary file
			for (Concept concept : manager.findAll(Concept.class)) {

				String uri = concept.getQName().getNamespaceURI()
						+ concept.getQName().getLocalPart();

				logger.debug("Adding concept " + uri);
				String preferredLabel = concept.getSkosPrefLabel();
				String pseudoPhrase = pseudoPhrase(preferredLabel);
				if (pseudoPhrase == null) {
					pseudoPhrase = preferredLabel;
				}

				if (pseudoPhrase.length() > 1) {
					addConcept(uri, pseudoPhrase, preferredLabel);
				}

				Set<String> altLabels = concept.getSkosAltLabels();
				for (String altLabel : altLabels) {
					addNonDescriptor(count, uri, altLabel);
					count++;
				}
				if (altLabels.size() > 0)
					hasAltLabels = true;


				String uriBroader = "";
				Set<Concept> broaders = concept.getSkosBroaders();
				try
				{
					for (Concept b : broaders) {
						uriBroader = b.getQName().getNamespaceURI()
								+ b.getQName().getLocalPart();
						addBroader(uri, uriBroader);
					}
				} catch (Exception e) {					
					logger.error("Failed to get broader:" + uriBroader + "for (" + concept.getQName() + ")", e);
				}

				String uriNarrower = "";
				Set<Concept> narrowers = concept.getSkosNarrowers();
				try
				{
					for (Concept n : narrowers) {
						uriNarrower = n.getQName().getNamespaceURI()
								+ n.getQName().getLocalPart();
						addNarrower(uri, uriNarrower);
					}
				} catch (Exception e) {					
					logger.error("Failed to get narrower:" + uriNarrower, e);
				}

				String uriRelated = "";
				Set<Concept> related = concept.getSkosRelated();
				try
				{
					for (Concept r: related) {
						uriRelated = r.getQName().getNamespaceURI()
							+ r.getQName().getLocalPart();
						addRelated(uri, uriRelated);
					}
				} catch (Exception e) {					
					logger.error("Failed to get related:" + uriRelated + "," + uri, e);
				}
				
				if (related.size() > 0)
					hasRelated = true;
				
			}

		} catch (Exception e) {
			logger.error(e);
			e.printStackTrace();
		}
		 
		// Close the writers
		vocabularyEN.close();
		vocabularyENrev.close();
		vocabularyREL.close();
		vocabularyUSE.close();
		
		logger.info("Importing to H2");
		Connection con = null;
		Statement s = null;
		try {

			con = getConnection();
			s = con.createStatement();
		
			StopWatch stopWatch = new Log4JStopWatch();
			
			// Bulk load KEA++ relations from temporary files
			s.execute("CREATE TABLE vocabulary_en (id varchar(512) , value varchar(1024)) AS SELECT * FROM CSVREAD('" + fileEN.getAbsolutePath() + "',null, 'UTF-8', '|');");
			s.execute("CREATE INDEX idx_kea_1 on vocabulary_en(id);");

			s.execute("CREATE TABLE vocabulary_enrev (id varchar(512) , value varchar(1024)) AS SELECT * FROM CSVREAD('" + fileENrev.getAbsolutePath() + "',null, 'UTF-8', '|');");
			s.execute("CREATE INDEX idx_kea_2 on vocabulary_enrev(id);");
			
			if (hasRelated)
				s.execute("CREATE TABLE vocabulary_rel (id varchar(512), value varchar(1024), relation varchar(20)) AS SELECT * FROM CSVREAD('" + fileREL.getAbsolutePath() + "',null, 'UTF-8', '|');");
			else
				s.execute("CREATE TABLE vocabulary_rel (id varchar(512), value varchar(1024), relation varchar(20));");

			s.execute("CREATE INDEX idx_kea_3 on vocabulary_rel(id);");
					
			if (hasAltLabels) 
				s.execute("CREATE TABLE vocabulary_use ( id varchar(512) , value varchar(1024)) AS SELECT * FROM CSVREAD('" + fileUSE.getAbsolutePath() + "',null, 'UTF-8', '|');");
			else
				s.execute("CREATE TABLE vocabulary_use ( id varchar(512) , value varchar(1024));");
			
			s.execute("CREATE INDEX idx_kea_4 on vocabulary_use(id);");		
			
			stopWatch.lap("H2 Created");

		} catch (Exception e) {
			logger.error(e);
		} finally {
			if (con != null) {
				try {
					if (con != null)
						con.close();
					if (s != null)
						s.close();
				} catch (Exception e) { }
			}
		}
		
		// Delete the temporary files
		fileEN.delete();
		fileENrev.delete();
		fileREL.delete();
		fileUSE.delete();
		stopwatch.lap("BuildSKOS");
	}

	
	private void addConcept(String uri, String pseudoPhrase, String preferredLabel) 
	{
		logger.trace("addConcept: " + uri + "," + pseudoPhrase + "," + preferredLabel);
		
		try {
			vocabularyEN.write(pseudoPhrase + "|" + uri + "\n");
			vocabularyENrev.write(uri + "|" + preferredLabel + "\n");			
		} catch (IOException e) {
			logger.error(e);
		}		
	}
	

	private void addBroader (String uri, String uriBroader) 
	{
		logger.trace("addBroader: " + uri + "," + uriBroader );
		
		try {
			vocabularyREL.write(uri + "|" + uriBroader + "|broader\n");
		} catch (IOException e) {
			logger.error(e);
		}		
	}
	
	
	private void addNarrower (String uri, String uriNarrower) 
	{
		logger.trace("addNarrower: " + uri + "," + uriNarrower );
		
		try {
			vocabularyREL.write(uri + "|" + uriNarrower + "|narrower\n");
		} catch (IOException e) {
			logger.error(e);
		}		
	}
		
	private void addRelated (String uri, String uriRelated) 
	{
		logger.trace("addRelated: " + uri + "," + uriRelated );	
		
		try {
			vocabularyREL.write(uri + "|" + uriRelated + "|related\n");
			vocabularyREL.write(uriRelated + "|" + uri + "|related\n");
		} catch (IOException e) {
			logger.error(e);
		}		
	}
	
	private void addNonDescriptor(int count, String uri, String altLabel) 
	{
		logger.trace("addNonDescriptor: " + count + "," + uri + "," + altLabel );
		
		String id_non_descriptor = "d_" + count;
		String avterm = pseudoPhrase(altLabel);
		
		try {
			if (avterm.length() > 2) {
				vocabularyEN.write(avterm + "|" + id_non_descriptor + "\n");
				vocabularyENrev.write(id_non_descriptor + "|" + altLabel + "\n");	
			}
			vocabularyUSE.write(id_non_descriptor + "|" + uri + "\n");
		} catch (IOException e) {
			logger.error(e);
		}	
	}

	@Override
	public String getID(String phrase) 
	{
		logger.trace("getID: " + phrase );
		
		String pseudo = pseudoPhrase(phrase);
		String id = null;
		if (pseudo != null) {
			Connection con = null;
			PreparedStatement ps = null;
			PreparedStatement ps2 = null;
			try {
				String sql = "select value from vocabulary_en where id = ?";
				
				con = getConnection();
				ps = con.prepareStatement(sql);
				ps.setString(1, pseudo);
				ResultSet rs = ps.executeQuery();
				while (rs.next())
					id = rs.getString(1);

				String sql2 = "select value from vocabulary_use where id = ?";

				ps2 = con.prepareStatement(sql2);
				ps2.setString(1, id);
				ResultSet rs2 = ps2.executeQuery();
				// TODO: To replicate KEA behavior, use the last entry, sadly
				while (rs2.next())
					id = rs2.getString(1);

			} catch (SQLException e) {
				e.printStackTrace();
				logger.error(e);
			} finally {
				try
				{
					if (con != null) 
						con.close();
					if (ps != null)
						ps.close();
					if (ps2 != null)
						ps2.close();
				} catch (SQLException e) {
					logger.error(e);
				}
			}
		}
		return id;
	}

	@Override
	public String getOrig(String id) 
	{
		logger.trace("getOrig: " + id );
		
		String orig = null;

		Connection con = null;
		PreparedStatement ps = null;
		try {
			String sql = "select value from vocabulary_enrev where id = ?";
			
			con = getConnection();
			ps = con.prepareStatement(sql);
			ps.setString(1, id);
			ResultSet rs = ps.executeQuery();
			if (rs.next())
				orig = rs.getString(1);

		} catch (SQLException e) {
			logger.error(e);
		} finally {
			try
			{
				if (con != null) 
					con.close();
				if (ps != null)
					ps.close();
			} catch (SQLException e) {
				logger.error(e);
			}
		}
			
		return orig;
	}

	@Override
	public Vector<String> getRelated(String id) 
	{
		logger.trace("getRelated: " + id );
		
		Vector<String> related = new Vector<String>();

		Connection con = null;
		PreparedStatement ps = null;
		try {
			String sql = "select value from vocabulary_rel where id = ? and relation = 'related'";
			
			con = getConnection();
			ps = con.prepareStatement(sql);
			ps.setString(1, id);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				related.add(rs.getString(1));
			}
		} catch (SQLException e) {
			logger.error(e);
		} finally {
			try
			{
				if (con != null) 
					con.close();
				if (ps != null)
					ps.close();
			} catch (SQLException e) {
				logger.error(e);
			}
		}
		return related;
		
	}

	@Override
	public Vector<String> getRelated(String id, String relation) 
	{
		logger.trace("getRelated: " + id + "," + relation);
		
		Vector<String> related = new Vector<String>();

		Connection con = null;
		PreparedStatement ps = null;
		try {
			String sql = "select value from vocabulary_rel where id = ? and relation = ?";
			
			con = getConnection();
			ps = con.prepareStatement(sql);
			ps.setString(1, id);
			ps.setString(2, relation);
			ResultSet rs = ps.executeQuery();
			while (rs.next()) {
				related.add(rs.getString(1));
			}

		} catch (SQLException e) {
			logger.error(e);
		} finally {
			try
			{
				if (con != null) 
					con.close();
				if (ps != null)
					ps.close();
			} catch (SQLException e) {
				logger.error(e);
			}
		}
		return related;

	}
	

	public static void main(String[] args) throws Exception {
		
		//NativeStore store = new NativeStore(new File("/usr/local/hive/hive-data/agrovoc/agrovocStore"));
		NativeStore store = new NativeStore(new File("/usr/local/hive/hive-data/mesh/meshStore"));
		//NativeStore store = new NativeStore(new File("/usr/local/hive/hive-data/nbii/nbiiStore"));
		//NativeStore store = new NativeStore(new File("/usr/local/hive/hive-data/tgn/tgnStore"));
        //String h2path = "/usr/local/hive/hive-data/agrovoc/agrovocH2/agrovoc";
        String h2path = "/usr/local/hive/hive-data/mesh/meshH2/mesh";
		//String h2path = "/usr/local/hive/hive-data/tgn/tgnH2/tgn";
		//String h2path = "/usr/local/hive/hive-data/nbii/nbiiH2/nbii";

		Repository repository = new SailRepository(store);
        repository.initialize();            
        ElmoModule module = new ElmoModule();           
        SesameManagerFactory factory = new SesameManagerFactory(module, repository);         
        // Create a new ElmoManager with default locale
        SesameManager manager = factory.createElmoManager(); 
		VocabularyH2 voc = new VocabularyH2("hive", h2path, "en", manager);
		Stopwords sw = new StopwordsEnglish("/usr/local/hive/hive-data/agrovoc//agrovocKEA/data/stopwords/stopwords_en.txt");
		voc.setStopwords(sw);
		voc.setStemmer(new PorterStemmer());
		long start = System.currentTimeMillis();
		voc.buildSKOS();
		long end = System.currentTimeMillis();
		long dur = end - start;
		System.out.println("Total Time: " + dur);
		voc.getID("Universities");
		
	}

	@Override
	public void buildUSE() throws Exception {
		// Not implemented
	}

	@Override
	public void buildREL() throws Exception {
		// Not supported		
	}
}
