import io.dbmaster.testng.BaseToolTestNGCase;

import static org.testng.Assert.assertTrue;
import org.testng.annotations.Test

import com.branegy.tools.api.ExportType;


public class SqlSeverSpaceInfoIT extends BaseToolTestNGCase {

    @Test
    public void test() {
        def parameters = [  :  ]
        String result = tools.toolExecutor("sqlserver-server-space-info", parameters).execute()
        assertTrue(result.contains("Database Name"), "Unexpected search results ${result}");
        assertTrue(result.contains("DB State"), "Unexpected search results ${result}");
    }
}
