package com.fourinachamber.fortyfive.screen.general

import com.badlogic.gdx.math.Vector2
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.InputEvent
import com.badlogic.gdx.scenes.scene2d.InputListener
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop.Payload
import com.badlogic.gdx.scenes.scene2d.utils.DragListener
import com.fourinachamber.fortyfive.screen.general.customActor.*
import com.fourinachamber.fortyfive.utils.obj
import kotlin.math.abs

abstract class CustomDragBehaviour(
    protected val dragAndDrop: DragAndDrop,
    actor: Actor,
) : DragAndDrop.Source(actor) {
}

@Suppress("unused")
abstract class CustomDropBehaviour(
    actor: Actor,
) : DragAndDrop.Target(actor)

/**
 * drags the object in the center
 */
open class CustomCenteredDragSource(
    //TODO test targets
    dragAndDrop: DragAndDrop,
    actor: Actor,
) : CustomDragBehaviour(dragAndDrop, actor) {


    init {
//        if (actor is HasOnjScreen)
//            actor.addListener(CustomClickListener(actor.screen))
        if (actor is HasOnjScreen)   //current possibilty for dragAndDrop
            actor.addListener(CustomClickListener(actor.screen)) //current possibilty for dragAndDrop
    }

    override fun dragStart(event: InputEvent?, x: Float, y: Float, pointer: Int): Payload? {
        val actor = actor
        if (actor is DisableActor && actor.isDisabled) return null
        if (actor is FocusableActor && !actor.isSelectable) return null
        if (actor is DraggableActor && !actor.isDraggable) return null
        if (actor !is HasOnjScreen) return null
        if ("draggableActor_draggingElement" in actor.screen.screenState) return null

        val obj = CustomExecutionPayload()
        obj.resetToStartIfNoTargetFound(actor, Vector2(actor.x, actor.y))
        if (actor is DraggableActor) {
            actor.actorDragStarted(actor, actor.screen, true)
            obj.resetScreenForDraggableActor(actor, actor.screen)
        }
        actor.isVisible = false

        addToPayload(actor, obj)

        val payload = Payload()
        payload.dragActor = actor
        payload.obj = obj
        return payload
    }

    open fun addToPayload(actor: Actor, obj: CustomExecutionPayload) {}


    override fun drag(event: InputEvent?, x: Float, y: Float, pointer: Int) {
        dragAndDrop.setDragActorPosition(actor.width / 2, -actor.height / 2)
        actor.isVisible = false
    }

    override fun dragStop(
        event: InputEvent?,
        x: Float,
        y: Float,
        pointer: Int,
        payload: Payload?,
        target: DragAndDrop.Target?
    ) {
        if (payload == null) return
        val obj = payload.obj as CustomExecutionPayload
        obj.onDragStop(target?.actor)
    }
}

class CustomClickListener(private val screen: OnjScreen) : InputListener() {

    var addedData: Vector2 = Vector2()
    private val tapSquareSize = 14f
    private var touchDownX: Float = -1f
    private var touchDownY: Float = -1f
    override fun touchDown(
        event: InputEvent,
        x: Float,
        y: Float,
        pointer: Int,
        button: Int
    ): Boolean { //TODO intercept mouse click to ButtonClick
        val actor = event.target
        if (actor !is OffSettable) return false
        if (actor is DisableActor && actor.isDisabled) return false
        if (actor is FocusableActor && !actor.isSelectable) return false
        if (actor is DraggableActor && !actor.isDraggable) return false
        if ("draggableActor_draggingElement" in screen.screenState) return false
        addedData = Vector2(-(actor.width / 2 - x), -(actor.height / 2 - y))

        if (actor is DraggableActor) actor.inDragPreview = true
        touchDownX = x
        touchDownY = y
        actor.drawOffsetX += addedData.x
        actor.drawOffsetY += addedData.y
        screen.draggedPreviewActor = actor
        if (screen.focusedActor == actor) screen.focusedActor = null
        actor.isVisible = false
        return true
    }

    override fun touchDragged(event: InputEvent, x: Float, y: Float, pointer: Int) {
        val actor = event.target
        if (actor !is OffSettable) return
        if (abs((touchDownX - x).toDouble()) > tapSquareSize || abs((touchDownY - y).toDouble()) > tapSquareSize) {
            actor.drawOffsetX -= addedData.x
            actor.drawOffsetY -= addedData.y
            addedData = Vector2()
            if (actor is DraggableActor) actor.inDragPreview = false
            screen.draggedPreviewActor = null
        }
    }

    override fun touchUp(event: InputEvent, x: Float, y: Float, pointer: Int, button: Int) {
        val actor = event.target
        if (actor !is OffSettable) return
        if (actor is DraggableActor) {
            actor.inDragPreview = false
        }
        actor.drawOffsetX -= addedData.x
        actor.drawOffsetY -= addedData.y
        addedData = Vector2()
        screen.draggedPreviewActor = null
        if (screen.draggedActor != actor) {
            actor.isVisible = true
            screen.focusedActor = actor
            if (actor is HoverStateActor) actor.isClicked = false
        }
    }
}


class CustomDropTarget(
    actor: Actor,
) : CustomDropBehaviour(actor) {
    override fun drag(
        source: DragAndDrop.Source?,
        payload: Payload?,
        x: Float,
        y: Float,
        pointer: Int
    ): Boolean {
        val actor = actor
        println("this is dragged over the following: ${actor.name}")
        if (actor is DisableActor && actor.isDisabled) return false
        if (actor is FocusableActor && !actor.isSelectable) return false
        if (actor is HasOnjScreen && !actor.screen.curSelectionParent.hasActor(actor)) return false
        return true
    }

    override fun drop(source: DragAndDrop.Source?, payload: Payload?, x: Float, y: Float, pointer: Int) {
        println("this was droppend on ${actor.name}")
    }

}


open class CustomExecutionPayload {

    /**
     * get executed on DragStop
     */
    val tasks: MutableList<(Actor?) -> Unit> = mutableListOf()

    /**
     * called when the drag is stopped
     */
    fun onDragStop(landedOn: Actor?) {
        for (task in tasks) task(landedOn)
    }

    /**
     * when the drag is stopped, the actor will be reset to [pos]
     */
    fun resetToStartIfNoTargetFound(actor: Actor, pos: Vector2) = tasks.add { target ->
        if (target == null) {
            actor.setPosition(pos.x, pos.y)
            actor.isVisible = true
        }
    }

    fun resetScreenForDraggableActor(actor: Actor, screen: OnjScreen) = tasks.add {
        screen.escapeSelectionHierarchy()
        if (actor is FocusableActor) screen.focusedActor = actor
    }
}
