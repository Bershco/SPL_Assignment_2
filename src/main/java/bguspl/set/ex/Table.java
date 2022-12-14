package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis > 0 ? env.config.tableDelayMillis : Dealer.practicallyZeroMS);
        } catch (InterruptedException ignored) {}
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     * @post - the slot is empty
     */
    public void removeCard(int slot) {
        Integer id = slotToCard[slot];
        if (id != null)
            cardToSlot[id] = null;
        slotToCard[slot] = null;
        synchronized (this) {
            try {
                wait(env.config.tableDelayMillis > 0 ? env.config.tableDelayMillis : Dealer.practicallyZeroMS);
            } catch (InterruptedException ignored) {
            }
        }
        env.ui.removeCard(slot);
    }

    /**
     * Removes all cards from a the slots received by parameter.
     * @param slots - the slots from which to remove the card.
     * @post - the slots are empty.
     */
    public void removeCards(int[] slots) {
        for (int i : slots) {
            removeCard(i);
        }
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     * @post - a token is placed in the relevant place for player
     */
    public void placeToken(int player, int slot) {
        env.ui.placeToken(player,slot);
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @post - a token is removed for the player
     */
    public void removeToken(int player, int slot) {
        env.ui.removeToken(player, slot);
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slots   - the slots from which to remove the tokens.
     */
    public void removeTokens(int player, int[] slots) {
        for (int i : slots) {
                removeToken(player,i);
        }
    }
    /**
     * Removes a token of a player from a grid slot.
     * @param currCardSlots - an array of slots where cards are placed
     * @post - removes all the cards in the array from table
     */
    public void removeCardsAndTokensInSlots(int[] currCardSlots) {
        for (int i : currCardSlots) {
            removeCard(i);
        }
    }
}
