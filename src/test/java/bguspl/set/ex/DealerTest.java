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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
            player1 = new Player(env, dealer, table, 0, false);
            player2 = new Player(env, dealer, table, 1, false);
            Player [] players= {player1,player2};
            dealer = new Dealer(env, table,players);
            assertInvariants();
        }

        @AfterEach
        void tearDown() {
            assertInvariants();
        }

        @Test
        void checkPlaceCardsOnTable(){
            dealer.setCardsOnTable();
            for(int i = 0; i<table.slotToCard.length; i++){
                assertNotEquals(table.slotToCard[i], (Object) null);
            }
            for(int i = 0; i<table.cardToSlot.length; i++){
                assertNotEquals(table.cardToSlot[i], (Object) null);
            }

        }
        @Test
        void checkRemoveAllCardsFromTable(){
            dealer.removeAllCards();
            for(int i = 0; i<table.slotToCard.length; i++){
                assertEquals(null,table.slotToCard[i]);
            }
            for(int i = 0; i<table.cardToSlot.length; i++){
                assertEquals(null,table.cardToSlot[i]);
            }
        }

    }

