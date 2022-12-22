package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

    @ExtendWith(MockitoExtension.class)
    class DealerTest {

        Dealer dealer;
        @Mock
        private Player player1;
        @Mock
        private Player player2;
        @Mock
        Util util;
        @Mock
        private UserInterface ui;
        @Mock
        private Table table;
        @Mock
        private Logger logger;

        void assertInvariants() {

        }

        @BeforeEach
        void setUp() {
            // purposely do not find the configuration files (use defaults here).
            Env env = new Env(logger, new Config(logger, ""), ui, util);
            dealer = new Dealer(env, table, new Player[]{new Player(env, dealer, table, 0, false)});
        }

        @AfterEach
        void tearDown() {
        }

        @Test
        void iStarted_ActuallyAddsToQueue(){
            Thread expectedThread = Thread.currentThread();
            dealer.iStarted();
            assertEquals(1,dealer.fairnessTerminatingSequence.size());

            Thread t = dealer.fairnessTerminatingSequence.remove();

            assertEquals(0,dealer.fairnessTerminatingSequence.size());

            assertEquals(expectedThread,t);
        }
        @Test
        void terminate_Works(){
            boolean expectedValue = false;
            assertEquals(expectedValue,dealer.terminate);
            dealer.terminate();
            assertEquals(!expectedValue,dealer.terminate);
        }

    }

