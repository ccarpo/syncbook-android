// UniFFI scaffolding emits a large metadata const array.
#![allow(clippy::large_const_arrays)]

use std::sync::Mutex;

use yrs::{
    doc::{OffsetKind, Options},
    encoding::read::Cursor,
    sync::{
        protocol::{Message, SyncMessage},
        MessageReader,
    },
    updates::decoder::{Decode, DecoderV1},
    updates::encoder::{Encode, Encoder, EncoderV1},
    GetString, ReadTxn, Text, Transact, Update, WriteTxn, Xml, XmlElementPrelim, XmlFragment,
    XmlOut, XmlTextPrelim,
};

pub trait SyncDocObserver: Send + Sync {
    fn changed(&self);
}

uniffi::include_scaffolding!("syncbook");

pub enum BlockKind {
    Paragraph,
    TaskItem,
}

pub struct Block {
    pub id: String,
    pub kind: BlockKind,
    pub text: String,
    pub checked: bool,
}

pub struct SyncDoc {
    doc: Mutex<yrs::Doc>,
    observers: Mutex<Vec<Box<dyn SyncDocObserver>>>,
}

impl SyncDoc {
    pub fn new() -> Self {
        Self {
            doc: Mutex::new(yrs::Doc::with_options(Options {
                offset_kind: OffsetKind::Utf16,
                ..Options::default()
            })),
            observers: Mutex::new(Vec::new()),
        }
    }

    pub fn apply_update(&self, update: Vec<u8>) {
        let Ok(update) = Update::decode_v1(&update) else {
            return;
        };
        let Ok(()) = self.doc.lock().unwrap().transact_mut().apply_update(update) else {
            return;
        };
        self.notify_observers();
    }

    pub fn state_vector(&self) -> Vec<u8> {
        self.doc
            .lock()
            .unwrap()
            .transact()
            .state_vector()
            .encode_v1()
    }

    pub fn sync_step1(&self) -> Vec<u8> {
        let vector = self.doc.lock().unwrap().transact().state_vector();
        let mut encoder = EncoderV1::new();
        Message::Sync(SyncMessage::SyncStep1(vector)).encode(&mut encoder);
        encoder.to_vec()
    }

    pub fn encode_update_message(&self, update: Vec<u8>) -> Vec<u8> {
        let mut encoder = EncoderV1::new();
        Message::Sync(SyncMessage::Update(update)).encode(&mut encoder);
        encoder.to_vec()
    }

    pub fn encode_state_as_update(&self, state_vector: Option<Vec<u8>>) -> Vec<u8> {
        let doc = self.doc.lock().unwrap();
        let vector = state_vector
            .and_then(|bytes| yrs::StateVector::decode_v1(&bytes).ok())
            .unwrap_or_default();
        let update = doc.transact().encode_state_as_update_v1(&vector);
        update
    }

    pub fn handle_message(&self, message: Vec<u8>) -> Vec<Vec<u8>> {
        let mut decoder = DecoderV1::new(Cursor::new(&message));
        let mut reader = MessageReader::new(&mut decoder);
        let doc = self.doc.lock().unwrap();
        let mut replies = Vec::new();
        let mut changed = false;

        while let Some(Ok(incoming)) = reader.next() {
            let response = match incoming {
                Message::Sync(SyncMessage::SyncStep1(vector)) => {
                    let update = doc.transact().encode_state_as_update_v1(&vector);
                    Some(Message::Sync(SyncMessage::SyncStep2(update)))
                }
                Message::Sync(SyncMessage::SyncStep2(update))
                | Message::Sync(SyncMessage::Update(update)) => {
                    if let Ok(update) = Update::decode_v1(&update) {
                        if doc.transact_mut().apply_update(update).is_ok() {
                            changed = true;
                        }
                    }
                    None
                }
                Message::Awareness(_) | Message::AwarenessQuery => None,
                _ => None,
            };
            if let Some(response) = response {
                let mut encoder = EncoderV1::new();
                response.encode(&mut encoder);
                replies.push(encoder.to_vec());
            }
        }

        drop(doc);
        if changed {
            self.notify_observers();
        }
        replies
    }

    pub fn blocks(&self) -> Vec<Block> {
        let doc = self.doc.lock().unwrap();
        let root = doc.get_or_insert_xml_fragment("prosemirror");
        let txn = doc.transact();
        top_level_blocks(&root, &txn)
    }

    pub fn insert_text(&self, block_id: String, offset: u32, text: String) {
        if block_id.is_empty() && self.blocks().is_empty() {
            let doc = self.doc.lock().unwrap();
            let mut txn = doc.transact_mut();
            let root = txn.get_or_insert_xml_fragment("prosemirror");
            let element = root.push_back(&mut txn, XmlElementPrelim::empty("paragraph"));
            element.push_back(&mut txn, XmlTextPrelim::new(""));
        }
        let target_id = if block_id.is_empty() {
            self.blocks().first().map(|block| block.id.clone())
        } else {
            Some(block_id)
        };
        if let Some(target_id) = target_id {
            self.with_text(&target_id, |txn, node| node.insert(txn, offset, &text));
        }
        self.notify_observers();
    }

    pub fn delete_text(&self, block_id: String, offset: u32, length: u32) {
        self.with_text(&block_id, |txn, node| {
            node.remove_range(txn, offset, length)
        });
        self.notify_observers();
    }

    pub fn set_checked(&self, block_id: String, checked: bool) {
        {
            let doc = self.doc.lock().unwrap();
            let txn = &mut doc.transact_mut();
            if let Some(item) = find_element(txn, &block_id) {
                item.insert_attribute(txn, "checked", checked.to_string());
            }
        }
        self.notify_observers();
    }

    pub fn split_block(&self, block_id: String, offset: u32) {
        {
            let doc = self.doc.lock().unwrap();
            let txn = &mut doc.transact_mut();
            let Some(current) = find_element(txn, &block_id) else {
                return;
            };
            let text = element_text(&current, txn);
            let (left, right) = split_utf16(&text, offset);
            current.remove_range(txn, 0, current.len(txn));
            append_block_text(&current, txn, left);
            if let Some(parent) = current.parent() {
                let tag = current.tag().to_string();
                match parent {
                    XmlOut::Fragment(parent) => {
                        let index = fragment_element_index(&parent, txn, &block_id);
                        let inserted = parent.insert(txn, index + 1, XmlElementPrelim::empty(tag));
                        if current.tag().as_ref() == "taskItem" {
                            let checked = current
                                .get_attribute(txn, "checked")
                                .map(|value| value.to_string(txn))
                                .unwrap_or_else(|| "false".to_string());
                            inserted.insert_attribute(txn, "checked", checked);
                        }
                        append_block_text(&inserted, txn, right);
                    }
                    XmlOut::Element(parent) => {
                        let index = element_element_index(&parent, txn, &block_id);
                        let inserted = parent.insert(txn, index + 1, XmlElementPrelim::empty(tag));
                        if current.tag().as_ref() == "taskItem" {
                            let checked = current
                                .get_attribute(txn, "checked")
                                .map(|value| value.to_string(txn))
                                .unwrap_or_else(|| "false".to_string());
                            inserted.insert_attribute(txn, "checked", checked);
                        }
                        append_block_text(&inserted, txn, right);
                    }
                    XmlOut::Text(_) => {}
                }
            }
        }
        self.notify_observers();
    }

    pub fn toggle_task_list(&self, block_id: String) {
        {
            let doc = self.doc.lock().unwrap();
            let txn = &mut doc.transact_mut();
            let Some(element) = find_element(txn, &block_id) else {
                return;
            };
            if element.tag().as_ref() == "paragraph" {
                let text = element_text(&element, txn);
                let Some(parent) = element.parent().and_then(XmlOut::into_xml_fragment) else {
                    return;
                };
                let index = parent
                    .successors(txn)
                    .position(|node| matches!(&node, XmlOut::Element(candidate) if element_id(candidate) == block_id))
                    .map(|index| index as u32)
                    .unwrap_or(parent.len(txn));
                parent.remove_range(txn, index, 1);
                let list = parent.insert(txn, index, XmlElementPrelim::empty("taskList"));
                let item = list.push_back(txn, XmlElementPrelim::empty("taskItem"));
                item.insert_attribute(txn, "checked", "false");
                let paragraph = item.push_back(txn, XmlElementPrelim::empty("paragraph"));
                paragraph.push_back(txn, XmlTextPrelim::new(text));
            } else if element.tag().as_ref() == "taskItem" {
                let text = element_text(&element, txn);
                let Some(list) = element.parent().and_then(XmlOut::into_xml_element) else {
                    return;
                };
                let Some(parent) = list.parent().and_then(XmlOut::into_xml_fragment) else {
                    return;
                };
                let index = fragment_element_index(&parent, txn, &element_id(&list));
                parent.remove_range(txn, index, 1);
                let paragraph = parent.insert(txn, index, XmlElementPrelim::empty("paragraph"));
                paragraph.push_back(txn, XmlTextPrelim::new(text));
            }
        }
        self.notify_observers();
    }

    pub fn observe(&self, observer: Box<dyn SyncDocObserver>) {
        self.observers.lock().unwrap().push(observer);
    }

    pub fn clear_observers(&self) {
        self.observers.lock().unwrap().clear();
    }

    fn notify_observers(&self) {
        for observer in self.observers.lock().unwrap().iter() {
            observer.changed();
        }
    }

    fn with_text<F>(&self, block_id: &str, operation: F)
    where
        F: FnOnce(&mut yrs::TransactionMut<'_>, yrs::XmlTextRef),
    {
        let doc = self.doc.lock().unwrap();
        let txn = &mut doc.transact_mut();
        if let Some(element) = find_element(txn, block_id) {
            if let Some(XmlOut::Text(text)) = element.get(txn, 0) {
                operation(txn, text);
            } else {
                let text = element.push_back(txn, XmlTextPrelim::new(""));
                operation(txn, text);
            }
        }
    }
}

impl Default for SyncDoc {
    fn default() -> Self {
        Self::new()
    }
}

fn find_element<'a>(txn: &'a yrs::TransactionMut<'a>, id: &str) -> Option<yrs::XmlElementRef> {
    let root = txn.get_xml_fragment("prosemirror")?;
    find_in_fragment(&root, txn, id)
}

fn find_in_fragment<T: yrs::ReadTxn>(
    fragment: &yrs::XmlFragmentRef,
    txn: &T,
    id: &str,
) -> Option<yrs::XmlElementRef> {
    for index in 0..fragment.len(txn) {
        if let Some(XmlOut::Element(element)) = fragment.get(txn, index) {
            if let Some(found) = find_in_element(&element, txn, id) {
                return Some(found);
            }
        }
    }
    None
}

fn find_in_element<T: yrs::ReadTxn>(
    element: &yrs::XmlElementRef,
    txn: &T,
    id: &str,
) -> Option<yrs::XmlElementRef> {
    if element_id(element) == id {
        return Some(element.clone());
    }
    for index in 0..element.len(txn) {
        if let Some(XmlOut::Element(child)) = element.get(txn, index) {
            if let Some(found) = find_in_element(&child, txn, id) {
                return Some(found);
            }
        }
    }
    None
}

fn top_level_blocks<T: yrs::ReadTxn>(root: &yrs::XmlFragmentRef, txn: &T) -> Vec<Block> {
    let mut blocks = Vec::new();
    for index in 0..root.len(txn) {
        let Some(XmlOut::Element(element)) = root.get(txn, index) else {
            continue;
        };
        match element.tag().as_ref() {
            "paragraph" => blocks.push(Block {
                id: element_id(&element),
                kind: BlockKind::Paragraph,
                text: element_text(&element, txn),
                checked: false,
            }),
            "taskList" => {
                for child_index in 0..element.len(txn) {
                    if let Some(XmlOut::Element(item)) = element.get(txn, child_index) {
                        if item.tag().as_ref() == "taskItem" {
                            blocks.push(task_block(&item, txn));
                        }
                    }
                }
            }
            _ => {}
        }
    }
    blocks
}

fn element_text<T: yrs::ReadTxn>(element: &yrs::XmlElementRef, txn: &T) -> String {
    (0..element.len(txn))
        .filter_map(|index| match element.get(txn, index) {
            Some(XmlOut::Text(text)) => Some(text.get_string(txn)),
            Some(XmlOut::Element(child)) => Some(element_text(&child, txn)),
            _ => None,
        })
        .collect()
}

fn fragment_element_index<T: yrs::ReadTxn>(parent: &yrs::XmlFragmentRef, txn: &T, id: &str) -> u32 {
    parent
        .successors(txn)
        .position(|node| matches!(&node, XmlOut::Element(candidate) if element_id(candidate) == id))
        .map(|index| index as u32)
        .unwrap_or(parent.len(txn))
}

fn element_element_index<T: yrs::ReadTxn>(parent: &yrs::XmlElementRef, txn: &T, id: &str) -> u32 {
    parent
        .successors(txn)
        .position(|node| matches!(&node, XmlOut::Element(candidate) if element_id(candidate) == id))
        .map(|index| index as u32)
        .unwrap_or(parent.len(txn))
}

fn append_block_text(element: &yrs::XmlElementRef, txn: &mut yrs::TransactionMut<'_>, text: &str) {
    if element.tag().as_ref() == "taskItem" {
        let paragraph = element.push_back(txn, XmlElementPrelim::empty("paragraph"));
        paragraph.push_back(txn, XmlTextPrelim::new(text));
    } else {
        element.push_back(txn, XmlTextPrelim::new(text));
    }
}

fn split_utf16(text: &str, offset: u32) -> (&str, &str) {
    let mut units = 0;
    for (index, character) in text.char_indices() {
        if units >= offset {
            return text.split_at(index);
        }
        units += character.len_utf16() as u32;
    }
    (text, "")
}

fn task_block<T: yrs::ReadTxn>(item: &yrs::XmlElementRef, txn: &T) -> Block {
    Block {
        id: element_id(item),
        kind: BlockKind::TaskItem,
        text: element_text(item, txn),
        checked: item
            .get_attribute(txn, "checked")
            .map(|value| value.to_string(txn) == "true")
            .unwrap_or(false),
    }
}

fn element_id(element: &yrs::XmlElementRef) -> String {
    format!("{:?}", yrs::XmlOut::Element(element.clone()).id())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sync_message(message: Message) -> Vec<u8> {
        let mut encoder = EncoderV1::new();
        message.encode(&mut encoder);
        encoder.to_vec()
    }

    fn sync_pair(left: &SyncDoc, right: &SyncDoc) {
        let left_step = sync_message(Message::Sync(SyncMessage::SyncStep1(
            yrs::StateVector::decode_v1(&left.state_vector()).unwrap(),
        )));
        for reply in right.handle_message(left_step) {
            left.handle_message(reply);
        }
        let right_step = sync_message(Message::Sync(SyncMessage::SyncStep1(
            yrs::StateVector::decode_v1(&right.state_vector()).unwrap(),
        )));
        for reply in left.handle_message(right_step) {
            right.handle_message(reply);
        }
    }

    #[test]
    fn docs_converge_through_sync_messages() {
        let left = SyncDoc::new();
        let right = SyncDoc::new();
        left.insert_text(String::new(), 0, String::new());
        let id = left.blocks()[0].id.clone();
        left.insert_text(id, 0, "hello".to_string());

        sync_pair(&left, &right);

        assert_eq!(right.blocks()[0].text, "hello");
    }

    #[test]
    fn concurrent_same_offset_inserts_both_survive() {
        let left = SyncDoc::new();
        let right = SyncDoc::new();
        left.insert_text(String::new(), 0, String::new());
        sync_pair(&left, &right);
        let left_id = left.blocks()[0].id.clone();
        let right_id = right.blocks()[0].id.clone();
        left.insert_text(left_id, 0, "left".to_string());
        right.insert_text(right_id, 0, "right".to_string());

        sync_pair(&left, &right);

        let text = &left.blocks()[0].text;
        assert!(text.contains("left"));
        assert!(text.contains("right"));
        assert_eq!(text, &right.blocks()[0].text);
    }

    #[test]
    fn concurrent_utf16_same_offset_inserts_both_survive() {
        let left = SyncDoc::new();
        let right = SyncDoc::new();
        left.insert_text(String::new(), 0, "ä😀".to_string());
        sync_pair(&left, &right);
        let left_id = left.blocks()[0].id.clone();
        let right_id = right.blocks()[0].id.clone();
        left.insert_text(left_id, 1, "left".to_string());
        right.insert_text(right_id, 1, "right".to_string());

        sync_pair(&left, &right);

        let text = &left.blocks()[0].text;
        assert!(text.contains("left"));
        assert!(text.contains("right"));
        assert_eq!(text, &right.blocks()[0].text);
    }

    #[test]
    fn checked_state_round_trips() {
        let doc = SyncDoc::new();
        doc.insert_text(String::new(), 0, String::new());
        let paragraph_id = doc.blocks()[0].id.clone();
        doc.toggle_task_list(paragraph_id);
        let task_id = doc.blocks()[0].id.clone();
        doc.set_checked(task_id, true);

        let block = &doc.blocks()[0];
        assert!(matches!(block.kind, BlockKind::TaskItem));
        assert!(block.checked);
    }

    #[test]
    fn snapshot_loads_into_fresh_document() {
        let original = SyncDoc::new();
        original.insert_text(String::new(), 0, String::new());
        let id = original.blocks()[0].id.clone();
        original.insert_text(id, 0, "snapshot".to_string());
        let snapshot = original.encode_state_as_update(None);

        let restored = SyncDoc::new();
        restored.apply_update(snapshot);

        assert_eq!(restored.blocks()[0].text, "snapshot");
    }
}
