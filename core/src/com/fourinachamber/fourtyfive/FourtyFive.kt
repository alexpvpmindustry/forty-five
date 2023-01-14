package com.fourinachamber.fourtyfive

import com.badlogic.gdx.Game
import com.badlogic.gdx.Gdx
import com.fourinachamber.fourtyfive.game.*
import com.fourinachamber.fourtyfive.onjNamespaces.CardsNamespace
import com.fourinachamber.fourtyfive.onjNamespaces.CommonNamespace
import com.fourinachamber.fourtyfive.screen.general.OnjScreen
import com.fourinachamber.fourtyfive.experimental.ScreenBuilder2
import com.fourinachamber.fourtyfive.experimental.ScreenNamespace
import com.fourinachamber.fourtyfive.experimental.StyleNamespace
import com.fourinachamber.fourtyfive.screen.general.ScreenBuilderFromOnj
import com.fourinachamber.fourtyfive.utils.FourtyFiveLogger
import onj.customization.OnjConfig

/**
 * main game object
 */
object FourtyFive : Game() {

    const val logTag = "fourty-five"

    /**
     * setting this variable will change the current screen and dispose the previous
     */
    var curScreen: OnjScreen? = null
        set(value) {
            FourtyFiveLogger.title("changing screen")
            field?.dispose()
            field = value
            setScreen(field)
        }


    var currentGame: GameController? = null

    override fun create() {
        init()
//        val cardGenerator = CardGenerator(Gdx.files.internal("cards/card_generator_config.onj"))
//        cardGenerator.prepare()
//        cardGenerator.generateCards()
        curScreen = ScreenBuilder2(Gdx.files.internal("screens/title_screen2.onj")).build()
//        curScreen = ScreenBuilderFromOnj(Gdx.files.internal("screens/intro_screen.onj")).build()
    }

    private fun init() {
        OnjConfig.registerNameSpace("Common", CommonNamespace)
        OnjConfig.registerNameSpace("Cards", CardsNamespace)
        OnjConfig.registerNameSpace("Experimental__Style", StyleNamespace) //TODO: experimental
        OnjConfig.registerNameSpace("Experimental__Screen", ScreenNamespace) //TODO: experimental
        FourtyFiveLogger.init()
        SaveState.read()
        GraphicsConfig.init()
    }

    override fun dispose() {
        FourtyFiveLogger.medium(logTag, "game closing")
        SaveState.write()
        super.dispose()
    }

}