package com.fourinachamber.fourtyfive.game

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.g2d.TextureAtlas
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.fourinachamber.fourtyfive.FourtyFive
import com.fourinachamber.fourtyfive.game.card.Card
import com.fourinachamber.fourtyfive.game.card.CardPrototype
import com.fourinachamber.fourtyfive.game.enemy.Enemy
import com.fourinachamber.fourtyfive.screen.gameComponents.EnemyArea
import com.fourinachamber.fourtyfive.screen.gameComponents.CardHand
import com.fourinachamber.fourtyfive.screen.gameComponents.CoverArea
import com.fourinachamber.fourtyfive.screen.gameComponents.Revolver
import com.fourinachamber.fourtyfive.screen.general.*
import com.fourinachamber.fourtyfive.utils.*
import onj.parser.OnjParser
import onj.parser.OnjSchemaParser
import onj.schema.OnjSchema
import onj.value.OnjArray
import onj.value.OnjNamedObject
import onj.value.OnjObject
import java.lang.Integer.max

/**
 * the Controller for the main game screen
 */
class GameController(onj: OnjNamedObject) : ScreenController() {

    private val cardConfigFile = onj.get<String>("cardsFile")
    private val cardAtlasFile = onj.get<String>("cardAtlasFile")
    private val cardDragAndDropBehaviour = onj.get<OnjNamedObject>("cardDragBehaviour")
    private val cardHandOnj = onj.get<OnjObject>("cardHand")
    private val revolverOnj = onj.get<OnjObject>("revolver")
    private val enemyAreaOnj = onj.get<OnjObject>("enemyArea")
    private val coverAreaOnj = onj.get<OnjObject>("coverArea")
    private val enemiesOnj = onj.get<OnjArray>("enemies")
    private val cardDrawActorName = onj.get<String>("cardDrawActor")
    private val destroyCardInstructionActorName = onj.get<String>("destroyCardInstructionActor")
    private val playerLivesLabelName = onj.get<String>("playerLivesLabelName")
    private val endTurnButtonName = onj.get<String>("endTurnButtonName")
    private val shootButtonName = onj.get<String>("shootButtonName")
    private val reservesLabelName = onj.get<String>("reservesLabelName")

    private val winScreen = onj.get<String>("winScreen")
    private val looseScreen = onj.get<String>("looseScreen")

    private val cardsToDrawInFirstRound = onj.get<Long>("cardsToDrawInFirstRound").toInt()
    private val cardsToDraw = onj.get<Long>("cardsToDraw").toInt()
    private val baseReserves = onj.get<Long>("reservesAtRoundBegin").toInt()
    val maxCards = onj.get<Long>("maxCards").toInt()
    private val shotEmptyDamage = onj.get<Long>("shotEmptyDamage").toInt()

    /**
     * stores the screenDataProvider for the game-screen
     */
    lateinit var curScreen: OnjScreen
        private set

    private var destroyCardPostProcessor: PostProcessor? = null

    lateinit var cardHand: CardHand
        private set
    lateinit var revolver: Revolver
        private set
    lateinit var enemyArea: EnemyArea
        private set
    lateinit var coverArea: CoverArea
        private set
    lateinit var cardDrawActor: Actor
        private set
    lateinit var destroyCardInstructionActor: Actor
        private set
    lateinit var shootButton: Actor
        private set
    lateinit var endTurnButton: Actor
        private set
    lateinit var playerLivesLabel: CustomLabel
        private set
    lateinit var reservesLabel: CustomLabel
        private set

    private var cardPrototypes: List<CardPrototype> = listOf()
    private val createdCards: MutableList<Card> = mutableListOf()
    private var bulletStack: MutableList<Card> = mutableListOf()
    private var coverCardStack: MutableList<Card> = mutableListOf()
    private val cardDragAndDrop: DragAndDrop = DragAndDrop()

    private var enemies: List<Enemy> = listOf()

    private var remainingCardsToDraw: Int? = null

    var curPlayerLives: Int
        set(value) {
            SaveState.playerLives = max(value, 0)
        }
        get() = SaveState.playerLives

    val playerLivesAtStart: Int = SaveState.playerLives

    private val timeline: Timeline = Timeline(mutableListOf()).apply {
        start()
    }

    private var isUIFrozen: Boolean = false

    /**
     * the current phase of the game
     */
    var currentPhase: Gamephase = Gamephase.FREE
        private set

    /**
     * counts up every round; starts at 0
     */
    var roundCounter: Int = 0
        private set

    /**
     * counts up every revolver turn; starts at 0
     */
    var turnCounter: Int = 0
        private set

    private var cardsToDrawDuringSpecialDraw: Int = 1

    var curReserves: Int = 0

    private var curGameAnims: MutableList<GameAnimation> = mutableListOf()

    private lateinit var defaultBullet: CardPrototype
    private lateinit var defaultCover: CardPrototype

    override fun init(onjScreen: OnjScreen) {
        SaveState.read()
        curScreen = onjScreen
        FourtyFive.currentGame = this

        FourtyFiveLogger.title("game starting")

        initCards()

        enemies = Enemy.getFrom(enemiesOnj)

        cardDrawActor = onjScreen.namedActorOrError(cardDrawActorName)
        onjScreen.removeActorFromRoot(cardDrawActor)

        destroyCardInstructionActor = onjScreen.namedActorOrError(destroyCardInstructionActorName)
        destroyCardInstructionActor.isVisible = false

        initButtons()
        initCardHand()
        initLabels()
        initRevolver()
        initEnemyArea()
        initCoverArea()
        initTemplateStringParams()

//        //TODO: this is really not good
//        //TODO: gotta fix this soon
//        onjScreen.afterMs(1000) {
//            onjScreen.resortRootZIndices()
//            onjScreen.invalidateEverything()
//        }
        changePhase(Gamephase.INITIAL_DRAW)
    }

    private fun initCards() {
        val onj = OnjParser.parseFile(cardConfigFile)
        cardsFileSchema.assertMatches(onj)
        onj as OnjObject

        val cardAtlas = TextureAtlas(Gdx.files.internal(cardAtlasFile))

        for (region in cardAtlas.regions) {
            curScreen.addDrawable("${Card.cardTexturePrefix}${region.name}", TextureRegionDrawable(region))
        }

        curScreen.addDisposable(cardAtlas)

        cardPrototypes = Card
            .getFrom(onj.get<OnjArray>("cards"), curScreen, ::initCard)
            .toMutableList()

        val startDeck: MutableList<Card> = mutableListOf()
        onj.get<OnjArray>("startDeck").value.forEach { entry ->

            entry as OnjObject
            val entryName = entry.get<String>("name")

            val card = cardPrototypes.firstOrNull { it.name == entryName }
                ?: throw RuntimeException("unknown card name is start deck: $entryName")

            repeat(entry.get<Long>("amount").toInt()) {
                startDeck.add(card.create())
            }
        }

        bulletStack = startDeck.filter { it.type == Card.Type.BULLET }.toMutableList()
        coverCardStack = startDeck.filter { it.type == Card.Type.COVER }.toMutableList()

        SaveState.additionalCards.forEach { entry ->
            val (cardName, amount) = entry

            val card = cardPrototypes.firstOrNull { it.name == cardName }
                ?: throw RuntimeException("unknown card name in saveState: $cardName")

            repeat(amount) {
                if (card.type == Card.Type.BULLET) bulletStack.add(card.create())
                else coverCardStack.add(card.create())
            }
        }

        bulletStack.shuffle()
        coverCardStack.shuffle()

        FourtyFiveLogger.debug(logTag, "bullet stack: $bulletStack")
        FourtyFiveLogger.debug(logTag, "cover stack: $coverCardStack")

        val defaultBulletName = onj.get<String>("defaultBullet")
        val defaultCoverName = onj.get<String>("defaultCover")

        defaultBullet = cardPrototypes
            .filter { it.type == Card.Type.BULLET }
            .firstOrNull { it.name == defaultBulletName }
            ?: throw RuntimeException("unknown default bullet: $defaultBulletName")

        defaultCover = cardPrototypes
            .filter { it.type == Card.Type.COVER }
            .firstOrNull { it.name == defaultCoverName }
            ?: throw RuntimeException("unknown default cover: $defaultBulletName")

    }

    private fun initCard(card: Card) {
        val behaviour = DragAndDropBehaviourFactory.dragBehaviourOrError(
            cardDragAndDropBehaviour.name,
            cardDragAndDrop,
            card.actor,
            cardDragAndDropBehaviour
        )
        cardDragAndDrop.addSource(behaviour)
        createdCards.add(card)
    }

    private fun initTemplateStringParams() = with(TemplateString) {
        bindParam("game.curReserves") { curReserves }
        bindParam("game.baseReserves") { baseReserves }
        bindParam("game.curPlayerLives") { curPlayerLives }
        bindParam("game.basePlayerLives") { playerLivesAtStart }
        bindParam("game.remainingCardsToDraw") { remainingCardsToDraw ?: 0 }
        bindParam("game.remainingCardsToDrawPluralS") { if (remainingCardsToDraw == 1) "" else "s" }
        bindParam("game.remainingBullets") { bulletStack.size }
        bindParam("game.remainingBulletsPluralS") { if (bulletStack.size == 1) "" else "s" }
        bindParam("game.remainingCovers") { coverCardStack.size }
        bindParam("game.remainingCoversPluralS") { if (coverCardStack.size == 1) "" else "s" }
    }

    private fun removeTemplateStringParams() = with(TemplateString) {
        removeParam("game.curReserves")
        removeParam("game.baseReserves")
        removeParam("game.curPlayerLives")
        removeParam("game.basePlayerLives")
        removeParam("game.remainingCardsToDraw")
        removeParam("game.remainingCardsToDrawPluralS")
        removeParam("game.remainingBullets")
        removeParam("game.remainingBulletsPluralS")
        removeParam("game.remainingCovers")
        removeParam("game.remainingCoversPluralS")
    }

    private fun changePhase(next: Gamephase) {
        if (next == currentPhase) return
        FourtyFiveLogger.debug(logTag, "changing phase from $currentPhase to $next")
        currentPhase.transitionAway(this)
        currentPhase = next
        currentPhase.transitionTo(this)
    }

    override fun update() {
        timeline.update()
        if (timeline.isFinished && isUIFrozen) unfreezeUI()
        if (!timeline.isFinished && !isUIFrozen) freezeUI()
        val iterator = curGameAnims.iterator()
        while (iterator.hasNext()) {
            val anim = iterator.next()
            if (anim.isFinished()) {
                anim.end()
                iterator.remove()
            }
            anim.update()
        }
    }

    /**
     * plays a gameAnimation
     */
    fun playGameAnimation(anim: GameAnimation) {
        FourtyFiveLogger.debug(logTag, "playing game animation: $anim")
        anim.start()
        curGameAnims.add(anim)
    }

    /**
     * changes the game to the SpecialDraw phase and sets the amount of cards to draw to [amount]
     */
    fun specialDraw(amount: Int) {
        if (currentPhase != Gamephase.FREE) return
        cardsToDrawDuringSpecialDraw = amount
        changePhase(Gamephase.SPECIAL_DRAW)
    }

    private fun initButtons() {
        shootButton = curScreen.namedActorOrError(shootButtonName)
        endTurnButton = curScreen.namedActorOrError(endTurnButtonName)
    }

    private fun initCardHand() {
        val curScreen = curScreen
        val cardHandName = cardHandOnj.get<String>("actorName")
        val cardHand = curScreen.namedActorOrError(cardHandName)
        if (cardHand !is CardHand) throw RuntimeException("actor named $cardHandName must be a CardHand")
        this.cardHand = cardHand
    }

    private fun initLabels() {
        val curScreen = curScreen
        val playerLives = curScreen.namedActorOrError(playerLivesLabelName)
        if (playerLives !is CustomLabel) throw RuntimeException("actor named $playerLivesLabelName must be a Label")
        playerLivesLabel = playerLives
        val reserves = curScreen.namedActorOrError(reservesLabelName)
        if (reserves !is CustomLabel) throw RuntimeException("actor named $reservesLabelName must be a Label")
        reservesLabel = reserves
    }

    private fun initCoverArea() {
        val curScreen = curScreen
        val coverAreaName = coverAreaOnj.get<String>("actorName")
        val coverArea = curScreen.namedActorOrError(coverAreaName)
        if (coverArea !is CoverArea) throw RuntimeException("actor named $coverAreaName must be a CoverArea")
        this.coverArea = coverArea
        val dropOnj = coverAreaOnj.get<OnjNamedObject>("dropBehaviour")
        coverArea.slotDropConfig = cardDragAndDrop to dropOnj
    }

    private fun initRevolver() {
        val curScreen = curScreen
        val revolverName = revolverOnj.get<String>("actorName")
        val revolver = curScreen.namedActorOrError(revolverName)
        if (revolver !is Revolver) throw RuntimeException("actor named $revolverName must be a Revolver")
        val dropOnj = revolverOnj.get<OnjNamedObject>("dropBehaviour")
        revolver.initDragAndDrop(cardDragAndDrop to dropOnj)
        this.revolver = revolver
    }

    private fun initEnemyArea() {
        val curScreen = curScreen

        val enemyAreaName = enemyAreaOnj.get<String>("actorName")
        val enemyArea = curScreen.namedActorOrError(enemyAreaName)
        if (enemyArea !is EnemyArea) throw RuntimeException("actor named $enemyAreaName must be a EnemyArea")

        enemyAreaOnj
            .get<OnjArray>("enemies")
            .value
            .forEach { nameOnj ->
                val name = nameOnj.value as String
                enemyArea.addEnemy(
                    enemies.firstOrNull { it.name == name} ?: throw RuntimeException("no enemy with name $name")
                )
            }

        this.enemyArea = enemyArea
    }

    /**
     * puts [card] in [slot] of the revolver (checks if the card is a bullet)
     */
    fun loadBulletInRevolver(card: Card, slot: Int) {
        if (card.type != Card.Type.BULLET || !card.allowsEnteringGame()) return
        if (!cost(card.cost)) return
        cardHand.removeCard(card)
        revolver.setCard(slot, card)
        FourtyFiveLogger.debug(logTag, "card $card entered revolver in slot $slot")
        card.onEnter()
        checkEffectsSingleCard(Trigger.ON_ENTER, card)
    }

    /**
     * adds a new cover to a slot in the cover area (checks if the card is a cover)
     */
    fun addCover(card: Card, slot: Int) {
        if (card.type != Card.Type.COVER || !card.allowsEnteringGame()) return
        if (!coverArea.acceptsCover(slot, roundCounter) || !cost(card.cost)) return
        coverArea.addCover(card, slot, roundCounter)
        cardHand.removeCard(card)
        FourtyFiveLogger.debug(logTag, "cover $card was placed in slot $slot")
        card.onEnter()
        checkEffectsSingleCard(Trigger.ON_ENTER, card)
    }

    /**
     * creates a new instance of the card named [name] and puts it in the hand of the player
     */
    fun putCardInHand(name: String) {
        val cardProto = cardPrototypes
            .firstOrNull { it.name == name }
            ?: throw RuntimeException("unknown card: $name")
        cardHand.addCard(cardProto.create())
        FourtyFiveLogger.debug(logTag, "card $name entered hand")
    }

    /**
     * shoots the revolver
     */
    fun shoot() {
        val revolver = revolver
        turnCounter++

        val cardToShoot = revolver.getCardInSlot(5)
        val rotateLeft = cardToShoot?.shouldRotateLeft ?: false
        val enemy = enemyArea.enemies[0]

        FourtyFiveLogger.debug(logTag, "revolver is shooting; turn = $turnCounter; cardToShoot = $cardToShoot")

        var enemyDamageTimeline: Timeline? = null
        var damageStatusEffectTimeline: Timeline? = null
        var turnStatusEffectTimeline: Timeline? = null
        var effectTimeline: Timeline? = null

        if (cardToShoot != null) {

            enemyDamageTimeline = Timeline.timeline {
                action {
                    if (cardToShoot.shouldRemoveAfterShot) revolver.removeCard(5)
                }
                include(enemy.damage(cardToShoot.curDamage))
                action { cardToShoot.afterShot() }
            }

            damageStatusEffectTimeline =
                enemy.executeStatusEffectsAfterDamage(cardToShoot.curDamage)
            turnStatusEffectTimeline = enemy.executeStatusEffectsAfterRevolverTurn()

            effectTimeline = cardToShoot.checkEffects(Trigger.ON_SHOT)
        }

        val finishTimeline = Timeline.timeline {
            action {

                checkCardModifierValidity()

                revolver
                    .slots
                    .mapNotNull { it.card }
                    .forEach(Card::onRevolverTurn)

                enemies.forEach(Enemy::onRevolverTurn)
            }
        }

        val timeline = Timeline.timeline {

            includeLater(
                { enemy.damagePlayer(shotEmptyDamage) },
                { cardToShoot == null }
            )

            enemyDamageTimeline?.let { include(it) }

            includeLater(
                { damageStatusEffectTimeline!! },
                { enemy.currentLives > 0 && damageStatusEffectTimeline != null }
            )
            includeLater(
                { effectTimeline!! },
                { enemy.currentLives > 0 && effectTimeline != null }
            )

            action {
                if (rotateLeft) revolver.rotateLeft() else revolver.rotate()

                FourtyFiveLogger.debug(logTag, "revolver rotated ${
                    if (rotateLeft) "left" else "right"
                }")
            }

            includeLater(
                { turnStatusEffectTimeline!! },
                { enemy.currentLives > 0 && turnStatusEffectTimeline != null }
            )

            includeLater(
                { finishTimeline },
                { enemy.currentLives > 0 }
            )

        }
        executeTimelineLater(timeline)
    }

    private fun checkCardModifierValidity() {
        FourtyFiveLogger.debug(logTag, "checking card modifiers")
        for (card in createdCards) if (card.inGame) card.checkModifierValidity()
    }

    fun endTurn() {
        onEndTurnButtonClicked()
    }

    /**
     * damages the player (plays no animation, calls loose when lives go below 0)
     */
    fun damagePlayer(damage: Int) {
        curPlayerLives -= damage
        FourtyFiveLogger.debug(logTag, "player got damaged; damage = $damage; curPlayerLives = $curPlayerLives")
        if (curPlayerLives <= 0) executeTimelineLater(Timeline.timeline {
            action { loose() }
        })
    }

    /**
     * adds reserves (plays no animations)
     */
    fun gainReserves(amount: Int) {
        curReserves += amount
        FourtyFiveLogger.debug(logTag, "player gained reserves; amount = $amount; curReserves = $curReserves")
    }

    /**
     * changes the game to the destroy phase
     */
    fun destroyCardPhase() = changePhase(Gamephase.CARD_DESTROY)

    /**
     * destroys a card in the revolver
     */
    fun destroyCard(card: Card) {
        revolver.removeCard(card)
        card.onDestroy()
        FourtyFiveLogger.debug(logTag, "destroyed card: $card")
        checkEffectsSingleCard(Trigger.ON_DESTROY, card)
        onCardDestroyed()
    }

    /**
     * checks whether a destroyable card is in the game
     */
    fun hasDestroyableCard(): Boolean {
        for (card in createdCards) if (card.inGame && card.type == Card.Type.BULLET) {
            return true
        }
        return false
    }

    private fun checkEffectsSingleCard(trigger: Trigger, card: Card) {
        FourtyFiveLogger.debug(logTag, "checking effects for card $card, trigger $trigger")
        card.checkEffects(trigger)?.let { executeTimelineLater(it) }
    }

    private fun checkEffectsActiveCards(trigger: Trigger) {
        FourtyFiveLogger.debug(logTag, "checking all active cards for trigger $trigger")
        val timeline = Timeline.timeline {
            for (card in createdCards) if (card.inGame) {
                val timeline = card.checkEffects(trigger)
                if (timeline != null) include(timeline)
            }
        }
        executeTimelineLater(timeline)
    }

    private fun checkStatusEffects() {
        FourtyFiveLogger.debug(logTag, "checking status effects")
        val timeline = Timeline.timeline {
            for (enemy in enemies) {
                val timeline = enemy.executeStatusEffects()
                if (timeline != null) include(timeline)
            }
        }
        executeTimelineLater(timeline)
    }

    /**
     * appends a timeline to the current timeline
     */
    fun executeTimelineLater(timeline: Timeline) {
        for (action in timeline.actions) this.timeline.appendAction(action)
    }

    private fun freezeUI() {
        isUIFrozen = true
        FourtyFiveLogger.debug(logTag, "froze UI")
        val shootButton = shootButton
        val endTurnButton = endTurnButton
        if (shootButton is DisableActor) shootButton.isDisabled = true
        if (endTurnButton is DisableActor) endTurnButton.isDisabled = true
        for (card in cardHand.cards) card.isDraggable = false
    }

    private fun unfreezeUI() {
        isUIFrozen = false
        FourtyFiveLogger.debug(logTag, "unfroze UI")
        val shootButton = shootButton
        val endTurnButton = endTurnButton
        if (shootButton is DisableActor) shootButton.isDisabled = false
        if (endTurnButton is DisableActor) endTurnButton.isDisabled = false
        for (card in cardHand.cards) card.isDraggable = true
    }

    private fun showCardDrawActor() {
        FourtyFiveLogger.debug(logTag, "displaying card draw actor")
        val viewport = curScreen.stage.viewport
        val cardDrawActor = cardDrawActor
        curScreen.addActorToRoot(cardDrawActor)
        cardDrawActor.isVisible = true
        cardDrawActor.setSize(viewport.worldWidth, viewport.worldHeight)
    }

    private fun showDestroyCardInstructionActor() {
        destroyCardInstructionActor.isVisible = true
    }

    private fun hideDestroyCardInstructionActor() {
        destroyCardInstructionActor.isVisible = false
    }

    private fun hideCardDrawActor() {
        FourtyFiveLogger.debug(logTag, "hiding card draw actor")
        curScreen.removeActorFromRoot(cardDrawActor)
        cardDrawActor.isVisible = false
    }

    /**
     * draws a bullet from the stack
     */
    fun drawBullet() {
        var cardsToDraw = remainingCardsToDraw ?: return
        val bullet = bulletStack.removeFirstOrNull() ?: defaultBullet.create()
        cardHand.addCard(bullet)
        cardsToDraw--
        this.remainingCardsToDraw = cardsToDraw
        FourtyFiveLogger.debug(logTag, "bullet was drawn; bullet = $bullet; cardsToDraw = $cardsToDraw")
        if (cardsToDraw <= 0) onAllCardsDrawn()
    }


    /**
     * draws a cover from the stack
     */
    fun drawCover() {
        var cardsToDraw = remainingCardsToDraw ?: return
        val cover = coverCardStack.removeFirstOrNull() ?: defaultCover.create()
        cardHand.addCard(cover)
        cardsToDraw--
        this.remainingCardsToDraw = cardsToDraw
        FourtyFiveLogger.debug(logTag, "bullet was drawn; bullet = $cover; cardsToDraw = $cardsToDraw")
        if (cardsToDraw <= 0) onAllCardsDrawn()
    }

    private fun cost(cost: Int): Boolean {
        if (cost > curReserves) return false
        curReserves -= cost
        SaveState.usedReserves += cost
        FourtyFiveLogger.debug(logTag, "$cost reserves were spent, curReserves = $curReserves")
        return true
    }

    override fun end() {
        FourtyFiveLogger.title("game ends")
        removeTemplateStringParams()
        FourtyFive.currentGame = null
        SaveState.write()
    }

    /**
     * called when an enemy was defeated
     */
    fun enemyDefeated(enemy: Enemy) {
        SaveState.enemiesDefeated++
        win()
    }

    private fun win() {
        FourtyFiveLogger.debug(logTag, "player won")
        FourtyFive.curScreen = ScreenBuilder(Gdx.files.internal(winScreen)).build()
        SaveState.write()
    }

    private fun loose() {
        FourtyFiveLogger.debug(logTag, "player lost")
        SaveState.reset()
        FourtyFive.curScreen = ScreenBuilder(Gdx.files.internal(looseScreen)).build()
    }

    private fun onAllCardsDrawn() = changePhase(currentPhase.onAllCardsDrawn())

    private fun onEndTurnButtonClicked() = changePhase(currentPhase.onEndTurnButtonClicked())

    private fun onCardDestroyed() = changePhase(currentPhase.onCardDestroyed())


    /**
     * the phases of the game
     */
    enum class Gamephase {

        /**
         * draws cards at the beginning of the round
         */
        INITIAL_DRAW {

            override fun transitionTo(gameController: GameController) = with(gameController) {
                roundCounter++
                FourtyFiveLogger.title("round: $roundCounter")
                remainingCardsToDraw =
                    (if (roundCounter == 1) cardsToDrawInFirstRound else cardsToDraw)
                        .coerceAtMost(maxCards - cardHand.cards.size)
                FourtyFiveLogger.debug(logTag, "drawing cards in initial draw: $remainingCardsToDraw")
                if (remainingCardsToDraw == 0) { //TODO: display this in some way
                    changePhase(ENEMY_REVEAL)
                    return
                }
                showCardDrawActor()
            }

            override fun transitionAway(gameController: GameController) = with(gameController) {
                hideCardDrawActor()
                remainingCardsToDraw = null
                checkStatusEffects()
                checkCardModifierValidity()
            }

            override fun onAllCardsDrawn(): Gamephase = ENEMY_REVEAL
            override fun onEndTurnButtonClicked(): Gamephase = INITIAL_DRAW
            override fun onCardDestroyed(): Gamephase = INITIAL_DRAW
        },

        /**
         * draws cards during the round, e.g. because of effects
         */
        SPECIAL_DRAW {

            override fun transitionTo(gameController: GameController) = with(gameController) {
                remainingCardsToDraw =
                    (cardsToDrawDuringSpecialDraw).coerceAtMost(maxCards - cardHand.cards.size)
                FourtyFiveLogger.debug(logTag, "drawing cards in special draw: $remainingCardsToDraw")
                if (remainingCardsToDraw == 0) { //TODO: display this in some way
                    changePhase(FREE)
                    return
                }
                showCardDrawActor()
            }

            override fun transitionAway(gameController: GameController) = with(gameController) {
                hideCardDrawActor()
                remainingCardsToDraw = null
            }

            override fun onAllCardsDrawn(): Gamephase = FREE
            override fun onEndTurnButtonClicked(): Gamephase = SPECIAL_DRAW
            override fun onCardDestroyed(): Gamephase = SPECIAL_DRAW
        },

        /**
         * player has to destroy a card
         */
        CARD_DESTROY {

            private var previousPostProcessor: PostProcessor? = null

            override fun transitionTo(gameController: GameController) = with(gameController) {

                if (destroyCardPostProcessor == null) {
                    destroyCardPostProcessor = GraphicsConfig.postProcessor("destroyCardPostProcessor")
                }

                showDestroyCardInstructionActor()

                previousPostProcessor = curScreen.postProcessor
                curScreen.postProcessor = destroyCardPostProcessor

                for (card in createdCards) if (card.inGame && card.type == Card.Type.BULLET) {
                    card.enterDestroyMode()
                }
            }

            override fun transitionAway(gameController: GameController) = with(gameController) {
                hideDestroyCardInstructionActor()
                curScreen.postProcessor = previousPostProcessor
                for (card in createdCards) if (card.inGame && card.type == Card.Type.BULLET) {
                    card.leaveDestroyMode()
                }
            }

            override fun onAllCardsDrawn(): Gamephase = CARD_DESTROY
            override fun onEndTurnButtonClicked(): Gamephase = CARD_DESTROY
            override fun onCardDestroyed(): Gamephase = FREE

        },

        /**
         * enemy reveals it's action
         */
        ENEMY_REVEAL {
            override fun transitionTo(gameController: GameController) = with(gameController) {
                enemies[0].chooseNewAction()
                curReserves = baseReserves
                checkEffectsActiveCards(Trigger.ON_ROUND_START)
                changePhase(FREE)
            }
            override fun transitionAway(gameController: GameController) {}
            override fun onAllCardsDrawn(): Gamephase = ENEMY_REVEAL
            override fun onEndTurnButtonClicked(): Gamephase = ENEMY_REVEAL
            override fun onCardDestroyed(): Gamephase = ENEMY_REVEAL
        },

        /**
         * main game phase
         */
        FREE {
            override fun transitionTo(gameController: GameController) {}
            override fun transitionAway(gameController: GameController) {}
            override fun onAllCardsDrawn(): Gamephase = FREE
            override fun onEndTurnButtonClicked(): Gamephase = ENEMY_ACTION
            override fun onCardDestroyed(): Gamephase = FREE
        },

        /**
         * enemy does its action
         */
        ENEMY_ACTION {

            override fun transitionTo(gameController: GameController) = with(gameController) {
                val timeline = Timeline.timeline {

                    val enemyBannerAnim = GraphicsConfig.bannerAnimation(false)
                    val playerBannerAnim = GraphicsConfig.bannerAnimation(true)

                    includeAction(enemyBannerAnim)
                    delay(GraphicsConfig.bufferTime)
                    enemies[0].doAction()?.let { include(it) }
                    delay(GraphicsConfig.bufferTime)
                    action { enemies[0].resetAction() }
                    includeAction(playerBannerAnim)
                    delay(GraphicsConfig.bufferTime)
                    action { changePhase(INITIAL_DRAW) }
                }

                executeTimelineLater(timeline)
            }

            override fun transitionAway(gameController: GameController) {}
            override fun onAllCardsDrawn(): Gamephase = ENEMY_ACTION
            override fun onEndTurnButtonClicked(): Gamephase = ENEMY_ACTION
            override fun onCardDestroyed(): Gamephase = ENEMY_ACTION
        }

        ;

        /**
         * transitions the game to this phase
         */
        abstract fun transitionTo(gameController: GameController)

        /**
         * transitions the game away from this phase
         */
        abstract fun transitionAway(gameController: GameController)

        /**
         * executed when all cards where drawn
         * @return the next phase
         */
        abstract fun onAllCardsDrawn(): Gamephase

        abstract fun onEndTurnButtonClicked(): Gamephase
        abstract fun onCardDestroyed(): Gamephase

    }

    companion object {

        const val logTag = "game"

        private val cardsFileSchema: OnjSchema by lazy {
            OnjSchemaParser.parseFile("onjschemas/cards.onjschema")
        }

    }

}
