package net.librec.recommender.context.rating;

import net.librec.BaseTestCase;
import net.librec.common.LibrecException;
import net.librec.conf.Configuration;
import net.librec.job.RecommenderJob;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

/**
 * @author szkb
 * @date 2018/12/16 16:38
 */
public class MyTrustSVDTestCase extends BaseTestCase {

    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    /**
     * Test the whole process of TrustSVD Recommender
     *
     * @throws ClassNotFoundException
     * @throws LibrecException
     * @throws IOException
     */
    @Test
    public void testRecommender() throws ClassNotFoundException, LibrecException, IOException {
        Configuration.Resource resource = new Configuration.Resource("rec/context/rating/mytrustsvd-test.properties");
        conf.addResource(resource);
        RecommenderJob job = new RecommenderJob(conf);
        job.runJob();
    }
}
