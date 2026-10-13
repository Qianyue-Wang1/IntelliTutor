from neo4j import GraphDatabase
import json
import os

# 配置 Neo4j 连接；容器中通过环境变量指向 neo4j 服务。
uri = os.getenv("NEO4J_URI", "neo4j://127.0.0.1:7687")
username = os.getenv("NEO4J_USERNAME") or os.getenv("NEO4J_USER", "neo4j")
password = os.environ["NEO4J_PASSWORD"]
database = os.getenv("NEO4J_DATABASE", "neo4j")

# 读取 kc_data.json
base_dir = os.path.dirname(os.path.abspath(__file__))
kc_path = os.path.join(base_dir, "..", "KT", "ours", "data", "kc_data.json")

with open(kc_path, "r", encoding="utf-8") as f:
    kc_data = json.load(f)

# 创建Neo4j驱动
driver = GraphDatabase.driver(uri, auth=(username, password))


def create_domain_nodes(tx, domain_groups):
    """创建领域(Domain)节点"""
    for domain_name in domain_groups:
        tx.run(
            "MERGE (d:Domain {name: $name})",
            name=domain_name,
        )


def create_topic_nodes(tx, taxonomy):
    """创建主题(Topic)节点并与 Domain 建立关系"""
    for domain_name, topics in taxonomy.items():
        for topic_name in topics.keys():
            tx.run(
                """
                MATCH (d:Domain {name: $domain_name})
                MERGE (t:Topic {name: $topic_name, domain: $domain_name})
                MERGE (d)-[:HAS_TOPIC]->(t)
                """,
                domain_name=domain_name,
                topic_name=topic_name,
            )


def create_subtopic_nodes(tx, taxonomy):
    """创建子主题(SubTopic)节点并与 Topic 建立关系"""
    for domain_name, topics in taxonomy.items():
        for topic_name, sub_topics in topics.items():
            for sub_topic_name in sub_topics:
                tx.run(
                    """
                    MATCH (t:Topic {name: $topic_name, domain: $domain_name})
                    MERGE (st:SubTopic {name: $sub_topic_name, domain: $domain_name, topic: $topic_name})
                    MERGE (t)-[:HAS_SUB_TOPIC]->(st)
                    """,
                    domain_name=domain_name,
                    topic_name=topic_name,
                    sub_topic_name=sub_topic_name,
                )


def create_knowledge_point_nodes(tx, knowledge_points):
    """创建知识点(KnowledgePoint)节点并与 SubTopic 建立关系"""
    for kp in knowledge_points:
        tx.run(
            """
            MATCH (st:SubTopic {name: $sub_topic, domain: $domain, topic: $topic})
            MERGE (kp:KnowledgePoint {id: $id, user_id: $user_id})
            SET kp.description      = $description,
                kp.domain           = $domain,
                kp.topic            = $topic,
                kp.sub_topic        = $sub_topic,
                kp.mastery          = $mastery,
                kp.exercise_records = $exercise_records
            MERGE (st)-[:HAS_KNOWLEDGE_POINT]->(kp)
            """,
            id=kp["id"],
            user_id="default",
            description=kp["description"],
            domain=kp["domain"],
            topic=kp["topic"],
            sub_topic=kp["sub_topic"],
            mastery=0.5,
            exercise_records=json.dumps([], ensure_ascii=False),
        )


def init_knowledge_graph(data):
    with driver.session(database=database) as session:
        print("正在创建 Domain 节点...")
        session.execute_write(create_domain_nodes, data["domain_groups"])

        print("正在创建 Topic 节点...")
        session.execute_write(create_topic_nodes, data["taxonomy"])

        print("正在创建 SubTopic 节点...")
        session.execute_write(create_subtopic_nodes, data["taxonomy"])

        print("正在创建 KnowledgePoint 节点...")
        session.execute_write(create_knowledge_point_nodes, data["knowledge_points"])


init_knowledge_graph(kc_data)
driver.close()
print("知识图谱初始化完成。")
